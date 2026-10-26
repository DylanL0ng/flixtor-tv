import { Match, onCleanup, onMount, Show, Switch, createSignal, type ParentProps } from 'solid-js';
import { QueryClientProvider, createQuery } from '@tanstack/solid-query';
import { Route, useLocation, useNavigate } from '@solidjs/router';
import { Column, Row, useFocusManager } from '@solidtv/solid/primitives';
import { HashRouter } from '@solidtv/solid/primitives/router';
import { AppContext, createApp, useApp } from './context';
import type { Platform, HostInfo } from '../platform/platform';
import type { Repository } from '../domain/repository';
import { Button, Menu, motion, palette } from '../ui/components';
import { Profiles } from '../features/profiles/Profiles';
import { Catalog } from '../features/catalog/Catalog';
import { DetailPage } from '../features/catalog/Detail';
import { Settings } from '../features/settings/Settings';
import { Pairing } from '../features/account/Pairing';
import { PlayerPage } from '../features/playback/Player';
import { createPlayback } from '../features/playback/playback';

export function AppRoot(props: { platform: Platform; host: HostInfo; repo: Repository }) {
  const app = createApp(props.platform, props.host, props.repo);
  return <AppContext.Provider value={app}><QueryClientProvider client={app.queries}>
    <HashRouter root={Shell}><Route path="/*all" component={Page} /></HashRouter>
  </QueryClientProvider></AppContext.Provider>;
}
function Shell(props: ParentProps) {
  const app = useApp(), navigate = useNavigate(), location = useLocation();
  const [exit, setExit] = createSignal(false);
  app.navigate = path => navigate(path);
  const playback = createPlayback(app.platform, app.repo, app.flixtor, app.anime, app.segments, app.profileId,
    destination => destination ? navigate(destination) : navigate(-1),
    account => {
      void app.queries.cancelQueries({ queryKey: ['account'] });
      app.queries.setQueryData(['account', app.authRevision()], account);
    });
  app.playback = playback;
  app.resumePending = async () => { const pending = app.pendingPlayback; if (!pending) return; app.pendingPlayback = undefined; navigate('/player', { replace: true }); await playback.start(pending.detail, pending.episode, pending.version); };
  useFocusManager();
  const account = createQuery(() => ({ queryKey: ['account', app.authRevision()], enabled: !!app.profileId(), queryFn: ({ signal }) => app.flixtor.account(signal), retry: false, staleTime: 60_000 }));
  const isPlayer = () => location.pathname === '/player';
  function back() {
    if (exit()) { setExit(false); return; }
    if ([...app.backHandlers.values()].reverse().some(handler => handler())) return;
    if (isPlayer()) {
      if (playback.state.bufferAhead) { void playback.buffer(false); return; }
      if (playback.state.stillWatching !== undefined || playback.state.chooser) { void playback.stop(); return; }
      if (playback.state.preview !== undefined) { playback.preview(); return; }
      if (playback.state.menu) { playback.menu(['settings', 'episodes'].includes(playback.state.menu) ? '' : 'settings'); return; }
      if (!playback.state.controls) { playback.interact(); return; }
      void playback.stop(); return;
    }
    if (location.pathname === '/home' || location.pathname === '/' || !app.profileId()) setExit(true); else navigate(-1);
  }
  const unsubscribe = app.platform.onEvent(event => {
    if (event.type !== 'key') return;
    const code = (event.value as { code: number }).code;
    if (code === 4) back();
    else if (isPlayer()) {
      playback.interact();
      if ([85, 126, 127].includes(code)) void playback.command(code === 126 || code === 85 && !playback.state.event?.playing ? 'play' : 'pause');
      if (code === 87) void playback.nextEpisode();
      if ([88, 89, 90].includes(code)) void playback.command('seek', { position: Math.max(0, (playback.state.event?.position || 0) + (code === 90 ? 10_000 : -10_000)) });
    }
  });
  const keydown = (event: KeyboardEvent) => {
    if (document.querySelector('dialog[open]')) return;
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); event.stopImmediatePropagation(); back(); }
    else if (isPlayer()) playback.interact();
  };
  window.addEventListener('keydown', keydown, true);
  onCleanup(() => { unsubscribe(); window.removeEventListener('keydown', keydown, true); playback.dispose(); });
  onMount(() => { if (app.host.platform === 'android') void app.updates.check(); });
  const scale = () => Math.min((1920 * (1 - 2 * app.data.fit.horizontal / 100)) / 1728, (1080 * (1 - 2 * app.data.fit.vertical / 100)) / 972);
  const nav = [['home', 'Home'], ['movies', 'Movies'], ['shows', 'TV shows'], ['anime', 'Anime'], ['search', 'Search'], ['list', 'My List'], ['continue', 'Continue Watching'], ['settings', 'Settings']];
  return <view width={1920} height={1080}>
    <Show when={!isPlayer()}><view width={1920} height={1080} color={palette.midnight} /></Show>
    <Show when={!app.profileId() || location.pathname === '/'} fallback={
      <Show when={!isPlayer()} fallback={props.children}>
        <Row x={1920 * app.data.fit.horizontal / 100} y={1080 * app.data.fit.vertical / 100} width={1728} height={972} scale={scale()} pivot={0} gap={40}>
          <Column width={250} height={930} gap={12} color={palette.panel} borderRadius={16} border={{ width: 1, color: palette.line }}>
            <text skipFocus width={250} height={80} fontSize={40} color={palette.accent}>FLIXTOR</text>
            {nav.map(([path, label]) => <Button id={`nav-${path}`} label={label} width={250} selected={location.pathname.startsWith('/' + path)} action={() => navigate('/' + path)} />)}
            <Button label={app.profile()?.name || 'Profiles'} width={250} action={() => { app.setProfileId(''); navigate('/'); }} />
            <text skipFocus width={250} height={70} fontSize={20} maxLines={2} color={account.data === 'VIP' ? palette.accent : palette.muted}>{account.isPending ? 'Checking account…' : account.isError ? 'Account unavailable' : account.data === 'VIP' ? 'VIP active' : account.data === 'SIGNED_IN' ? 'Signed in · VIP unverified' : account.data === 'EXPIRED' ? 'Membership expired' : 'Guest'}</text>
          </Column>
          <view width={1430} height={950} forwardFocus={0}>{props.children}</view>
        </Row>
      </Show>
    }><Profiles /></Show>
    <Show when={app.notice()}><view x={300} y={985} width={1320} height={65} color={palette.panel} borderRadius={12} border={{ width: 1, color: palette.line }}><text x={24} y={16} width={1270} height={40} fontSize={26} color={palette.foreground}>{app.notice()}</text></view></Show>
    <Show when={exit()}><view width={1920} height={1080} color={0x06152ee6} alpha={1} transition={{ alpha: motion.fade }}><view x={360} y={240} width={1200} height={600} color={palette.panel} borderRadius={20} border={{ width: 1, color: palette.line }} scaleX={1} scaleY={1} transition={{ scaleX: motion.enter, scaleY: motion.enter }}>
      <Menu width={1160} title="Exit FlixTor?" options={[{ id: 'stay', label: 'Stay', action: () => setExit(false) }, { id: 'exit', label: 'Exit', action: () => void app.platform.call('exit') }]} />
    </view></view></Show>
  </view>;
}
function Page() {
  const location = useLocation();
  return <Switch>
    <Match when={location.pathname === '/detail'}><DetailPage /></Match>
    <Match when={location.pathname.startsWith('/settings')}><Settings /></Match>
    <Match when={location.pathname === '/pair'}><Pairing /></Match>
    <Match when={location.pathname === '/player'}><PlayerPage /></Match>
    <Match when={true}><Catalog /></Match>
  </Switch>;
}
