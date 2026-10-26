import { Match, onCleanup, onMount, Show, Switch, Suspense, lazy, createSignal, createEffect, type ParentProps } from 'solid-js';
import { QueryClientProvider, createQuery } from '@tanstack/solid-query';
import { Route, useLocation, useNavigate } from '@solidjs/router';
import { Column, Row, useFocusManager } from '@solidtv/solid/primitives';
import { HashRouter } from '@solidtv/solid/primitives/router';
import { AppContext, createApp, useApp } from './context';
import type { Platform, HostInfo } from '../platform/platform';
import type { Repository } from '../domain/repository';
import { Button, Glass, Menu, palette, motion } from '../ui/components';
import { activeScope, announce, restoreFocus } from '../ui/focus';
import { Backdrop } from '../ui/artwork';
import { Overlay } from '../ui/Overlay';
import { InputDialog } from '../ui/Input';
import { Profiles } from '../features/profiles/Profiles';
import { Catalog } from '../features/catalog/Catalog';
import { createPlayback } from '../features/playback/playback';
import { Config } from '@solidtv/solid';
import { setReducedMotion, setReducedTransparency } from '../ui/theme';
const DetailPage = lazy(() => import('../features/catalog/Detail').then(module => ({ default: module.DetailPage })));
const Settings = lazy(() => import('../features/settings/Settings').then(module => ({ default: module.Settings })));
const Pairing = lazy(() => import('../features/account/Pairing').then(module => ({ default: module.Pairing })));
const PlayerPage = lazy(() => import('../features/playback/Player').then(module => ({ default: module.PlayerPage })));

export function AppRoot(props: { platform: Platform; host: HostInfo; repo: Repository }) {
  const app = createApp(props.platform, props.host, props.repo);
  return <AppContext.Provider value={app}><QueryClientProvider client={app.queries}>
    <HashRouter root={Shell}><Route path="/*all" component={Page} /></HashRouter>
  </QueryClientProvider></AppContext.Provider>;
}
function Shell(props: ParentProps) {
  const app = useApp(), navigate = useNavigate(), location = useLocation();
  const [exit, setExit] = createSignal(false), [profileMenu, setProfileMenu] = createSignal(false);
  app.navigate = path => navigate(path);
  const playback = createPlayback(app.platform, app.repo, app.flixtor, app.anime, app.segments, app.profileId,
    destination => destination ? navigate(destination) : navigate(-1), account => {
      void app.queries.cancelQueries({ queryKey: ['account'] }); app.queries.setQueryData(['account', app.authRevision()], account);
    });
  app.playback = playback;
  app.resumePending = async () => { const pending = app.pendingPlayback; if (!pending) return; app.pendingPlayback = undefined; navigate('/player', { replace: true }); await playback.start(pending.detail, pending.episode, pending.version); };
  useFocusManager({ Back: null, Last: null });
  const account = createQuery(() => ({ queryKey: ['account', app.authRevision()], enabled: !!app.profileId(), queryFn: ({ signal }) => app.flixtor.account(signal), retry: false, staleTime: 60_000 }));
  const isPlayer = () => location.pathname === '/player', profiles = () => !app.profileId() || location.pathname === '/';
  const navVisible = () => !profiles() && !isPlayer() && location.pathname !== '/detail' && location.pathname !== '/pair';
  createEffect(() => {
    const settings = app.data.skip;
    const reduced = settings['display:motion'] === 'reduced' || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    Config.animationsEnabled = !reduced; setReducedMotion(reduced); setReducedTransparency(settings['display:transparency'] === 'reduced');
  });
  function back() {
    if ([...app.backHandlers.values()].reverse().some(handler => handler())) return;
    if (isPlayer()) {
      if (playback.state.bufferAhead) { void playback.buffer(false); return; }
      if (playback.state.stillWatching !== undefined || playback.state.chooser) { void playback.stop(); return; }
      if (playback.state.preview !== undefined) { playback.preview(); return; }
      if (playback.state.menu) { playback.menu(['settings', 'episodes'].includes(playback.state.menu) ? '' : 'settings'); return; }
      if (playback.state.countdown !== undefined) { playback.cancelNext(); return; }
      if (!playback.state.controls) { playback.interact(); return; }
      void playback.stop(); return;
    }
    if (location.pathname === '/home' || profiles()) setExit(true); else navigate(-1);
  }
  const media = (code: number) => {
    if (!isPlayer() || activeScope() !== 'screen' || document.querySelector('dialog[open]')) return;
    playback.interact();
    if ([85, 126, 127].includes(code)) void playback.command(code === 126 || code === 85 && !playback.state.event?.playing ? 'play' : 'pause');
    if (code === 87) void playback.nextEpisode();
    if ([88, 89, 90].includes(code)) void playback.command('seek', { position: Math.max(0, Math.min(playback.state.event?.duration || 0, (playback.state.event?.position || 0) + (code === 90 ? 10_000 : -10_000))) });
  };
  const unsubscribe = app.platform.onEvent(event => { if (event.type === 'key') { const code = (event.value as { code: number }).code; if (code === 4) back(); else media(code); } });
  const keydown = (event: KeyboardEvent) => {
    if (document.querySelector('dialog[open]') || event.isComposing) return;
    const editable = event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement;
    if (event.repeat && ['Enter', ' ', 'Escape', 'BrowserBack'].includes(event.key)) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); event.stopImmediatePropagation(); back(); return; }
    if (event.key === 'Tab') {
      const controls = [...document.querySelectorAll<HTMLElement>(`[data-scope="${activeScope()}"]`)].filter(el => !el.hidden && !(el as HTMLButtonElement).disabled && el.tabIndex >= 0);
      if (controls.length) { const index = controls.indexOf(document.activeElement as HTMLElement); const next = (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length; event.preventDefault(); event.stopImmediatePropagation(); controls[next].focus(); }
      return;
    }
    if (editable) return;
    if (event.key === 'Enter') event.preventDefault();
    if (event.key === '/' && activeScope() === 'screen' && !isPlayer() && !profiles()) { event.preventDefault(); event.stopImmediatePropagation(); navigate('/search'); }
    if (isPlayer()) {
      playback.interact();
      if (event.key === ' ' || event.key === 'MediaPlayPause') { event.preventDefault(); event.stopImmediatePropagation(); media(85); }
    }
  };
  window.addEventListener('keydown', keydown, true);
  onCleanup(() => { unsubscribe(); window.removeEventListener('keydown', keydown, true); playback.dispose(); });
  onMount(() => { if (app.host.platform === 'android') void app.updates.check(); });
  createEffect(() => { if (app.notice()) announce(app.notice()); });
  const scale = () => Math.min((1920 * (1 - 2 * app.data.fit.horizontal / 100)) / 1728, (1080 * (1 - 2 * app.data.fit.vertical / 100)) / 972);
  const nav = [['home', 'Home', 145], ['movies', 'Movies', 155], ['shows', 'TV shows', 180], ['anime', 'Anime', 145], ['list', 'My List', 155], ['search', 'Search', 170]] as const;
  return <view width={1920} height={1080}>
    <Show when={!isPlayer()}><Backdrop url={profiles() ? undefined : app.stage()?.backdrop || app.stage()?.poster} poster={!app.stage()?.backdrop} /></Show>
    <Show when={!profiles()} fallback={<Profiles />}>
      <Show when={!isPlayer()} fallback={props.children}>
        <Column x={1920 * app.data.fit.horizontal / 100} y={1080 * app.data.fit.vertical / 100} width={1728} height={972} scale={scale()} pivot={0} gap={30} scroll="none">
          <Show when={navVisible()}><Row width={1728} height={94} gap={30} scroll="none">
            <text skipFocus width={220} height={68} fontSize={37} fontFamily="Roboto700" color={palette.foreground}>FLIXTOR</text>
            <view width={1030} height={86}><Glass width={1030} height={86} radius={43} />
              <Show when={nav.some(([path]) => location.pathname === '/' + path)}><view skipFocus x={16 + nav.slice(0, Math.max(0, nav.findIndex(([path]) => location.pathname === '/' + path))).reduce((sum, entry) => sum + entry[2] + 4, 0)} y={9} width={nav.find(([path]) => location.pathname === '/' + path)?.[2] || 145} height={68} color={0x71859b66} borderRadius={34} transition={{ x: motion.slide, width: motion.slide }} /></Show>
              <Row x={16} y={9} width={998} height={68} gap={4}>
              {nav.map(([path, label, width]) => <Button id={`nav-${path}`} label={label} width={width} icon={path === 'search' ? 'search' : undefined} quiet selected={location.pathname === '/' + path} selectionFill={false} action={() => navigate('/' + path)} />)}
            </Row></view>
            <Button label={app.profile()?.name || 'Profiles'} width={240} icon="person" id="profile-menu" action={() => setProfileMenu(true)} />
            <Button label="Settings" icon="settings" iconOnly width={68} action={() => navigate('/settings')} />
          </Row></Show>
          <view width={1728} height={navVisible() ? 848 : 972} clipping forwardFocus={0}>{props.children}</view>
        </Column>
      </Show>
    </Show>
    <Show when={app.notice()}><view x={460} y={930} width={1000} height={90} zIndex={1000}><Glass width={1000} height={90} solid /><text skipFocus x={30} y={23} width={940} height={60} maxLines={2} fontSize={26}>{app.notice()}</text></view></Show>
    <Show when={profileMenu()}><Overlay title={app.profile()?.name || 'Profile'} close={() => setProfileMenu(false)} width={780} height={660}>
      <Menu width={700} height={588} title={app.profile()?.name || 'Profile'} options={[
        { id: 'continue', label: 'Continue Watching', action: () => { setProfileMenu(false); navigate('/continue'); } },
        { id: 'settings', label: 'Settings', action: () => { setProfileMenu(false); navigate('/settings'); } },
        { id: 'switch', label: 'Switch profile', action: () => { setProfileMenu(false); app.lastProfile = app.profileId(); app.setProfileId(''); navigate('/'); } },
        { id: 'status', label: account.isPending ? 'Checking account…' : account.isError ? 'Account unavailable' : account.data === 'VIP' ? 'VIP active' : account.data === 'SIGNED_IN' ? 'Signed in · VIP unverified' : account.data === 'EXPIRED' ? 'Membership expired' : 'Guest', action: () => { setProfileMenu(false); navigate('/settings/account'); } },
        { id: 'close', label: 'Close', action: () => setProfileMenu(false) },
      ]} />
    </Overlay></Show>
    <Show when={exit()}><Overlay title="Exit FlixTor TV?" close={() => setExit(false)} width={860} height={450}>
      <Column width={780} height={378} gap={28} scroll="none"><text skipFocus width={780} height={64} fontSize={42} fontFamily="Roboto700">Exit FlixTor TV?</text>
        <text skipFocus width={780} height={65} fontSize={28} color={palette.muted}>Your place in every story is saved.</text>
        <Row width={780} height={68} gap={20}><Button label="Stay" autofocus primary action={() => setExit(false)} /><Button label="Exit app" action={() => void app.platform.call('exit')} /></Row>
      </Column>
    </Overlay></Show>
    <Show when={app.inputRequest()} keyed>{request => <InputDialog request={request} />}</Show>
  </view>;
}
function Page() {
  const location = useLocation();
  return <Suspense fallback={<text skipFocus width={1400} height={80} fontSize={30} color={palette.muted}>Loading…</text>}><Switch>
    <Match when={location.pathname === '/detail'}><DetailPage /></Match>
    <Match when={location.pathname.startsWith('/settings')}><Settings /></Match>
    <Match when={location.pathname === '/pair'}><Pairing /></Match>
    <Match when={location.pathname === '/player'}><PlayerPage /></Match>
    <Match when={true}><Catalog /></Match>
  </Switch></Suspense>;
}
