import { createEffect, createMemo, createSignal, For, onMount, onCleanup, Show } from 'solid-js';
import { createQuery } from '@tanstack/solid-query';
import { useLocation, useNavigate, useSearchParams } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, motion, palette, Poster, restoreFocus } from '../../ui/components';
import { failureOf, titleKey, type Shelf, type Title } from '../../domain/models';
import { failureMessages } from '../../ui/copy';

export function Catalog() {
  const app = useApp(), location = useLocation(), navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [highlighted, setHighlighted] = createSignal<Title>();
  const [heroAlpha, setHeroAlpha] = createSignal(1);
  createEffect(() => {
    highlighted();
    setHeroAlpha(0.2);
    const timer = setTimeout(() => setHeroAlpha(1), 30);
    onCleanup(() => clearTimeout(timer));
  });
  const destination = () => location.pathname.slice(1) || 'home';
  const query = () => String(params.q || ''), sort = () => String(params.sort || 'latest');
  const page = () => Math.max(1, Number(params.page) || 1), windowPage = () => Math.max(0, Number(params.window) || 0);
  const remote = createQuery(() => ({
    queryKey: ['catalog', destination(), query(), sort(), page(), app.profileId(), app.authRevision()],
    enabled: !['list', 'continue'].includes(destination()),
    queryFn: async ({ signal }): Promise<Shelf[]> => {
      const screen = destination();
      if (screen === 'home') return app.flixtor.home(signal);
      const titles = screen === 'anime' ? await app.anime.browse(query(), page(), signal) : screen === 'search' ? await app.flixtor.search(query(), page(), signal) : await app.flixtor.browse(screen === 'shows' ? 'tvshows' : 'movies', page(), sort(), signal);
      return [{ label: screen === 'search' ? `Results for ${query()}` : screen === 'anime' ? 'Anime' : screen === 'shows' ? 'TV shows' : 'Movies', titles }];
    }, retry: (count, error) => failureOf(error) === 'NETWORK' && count < 1,
  }));
  const shelves = createMemo(() => {
    const library = app.data.libraries[app.profileId()];
    const continuing = library?.viewing.filter(v => v.duration <= 0 || v.position < v.duration - 30_000).map(v => v.title) || [];
    if (destination() === 'list') return [{ label: 'My List', titles: library?.favorites || [] }];
    if (destination() === 'continue') return [{ label: 'Continue Watching', titles: continuing }];
    return destination() === 'home' && continuing.length ? [{ label: 'Continue Watching', titles: continuing }, ...(remote.data || [])] : remote.data || [];
  });
  const [shelfIndex, setShelfIndex] = createSignal(0);
  const shelf = () => shelves()[Math.min(shelfIndex(), shelves().length - 1)];
  const titles = () => shelf()?.titles || [];
  const visible = () => titles().slice(windowPage() * 5, windowPage() * 5 + 5);
  createEffect(() => { destination(); query(); sort(); page(); setShelfIndex(0); setHighlighted(undefined); });
  const [delayed, setDelayed] = createSignal(false);
  createEffect(() => { const fetching = remote.isFetching; const timer = setTimeout(() => setDelayed(fetching), 120); if (!fetching) setDelayed(false); onCleanup(() => clearTimeout(timer)); });
  onMount(() => { setTimeout(() => restoreFocus(String(params.focus || app.lastFocus || '')), 0); });
  const open = (t: Title) => { app.setSelected(t); app.titleCache.set(titleKey(t), t); if (app.titleCache.size > 256) app.titleCache.delete(app.titleCache.keys().next().value!); app.lastFocus = titleKey(t); navigate(`/detail?title=${encodeURIComponent(titleKey(t))}`); };
  const search = () => app.run(async () => { const value = await app.input(destination() === 'anime' ? 'Search anime' : 'Search', query()); if (value !== null) setParams({ q: value.trim().slice(0, 64), page: 1, window: 0 }); });
  return <Column width={1420} height={900} gap={24} autofocus>
    <view skipFocus width={1400} height={220} alpha={heroAlpha()} transition={{ alpha: motion.fade }}>
      <text skipFocus width={1380} height={80} fontSize={58} maxLines={1} color={palette.foreground}>{highlighted()?.name || (destination() === 'home' ? `Welcome, ${app.profile()?.name}` : shelf()?.label || 'Browse')}</text>
      <text skipFocus y={95} width={1300} height={110} fontSize={26} color={palette.muted} maxLines={3}>{highlighted() ? [highlighted()?.year, highlighted()?.genres, highlighted()?.synopsis].filter(Boolean).join('  ·  ') : 'Choose something for tonight.'}</text>
    </view>
    <Row width={1400} height={68} gap={16}>
      <Button label="Search" width={220} action={() => { if (destination() === 'search' || destination() === 'anime') search(); else navigate('/search'); }} />
      <Show when={!['home', 'list', 'continue', 'anime', 'search'].includes(destination())}>
        <For each={['latest', 'best', 'name']}>{value => <Button label={value === 'latest' ? 'Latest' : value === 'best' ? 'Top rated' : 'A–Z'} width={210} selected={sort() === value} action={() => setParams({ sort: value, page: 1, window: 0 })} />}</For>
      </Show>
      <Show when={destination() === 'search' && query() || destination() === 'anime' && query()}><Button label="Reset search" width={250} action={() => setParams({ q: undefined, page: 1, window: 0 })} /></Show>
    </Row>
    <Show when={shelves().length > 1}><Row width={1400} height={68} gap={16} scroll="edge"><For each={shelves()}>{(s, i) => <Button label={s.label} width={270} selected={shelfIndex() === i()} action={() => { setShelfIndex(i()); setParams({ window: 0 }); }} />}</For></Row></Show>
    <Show when={remote.isPending && remote.isEnabled}><text skipFocus width={1400} height={70} fontSize={32} color={palette.muted}>Loading catalogue…</text></Show>
    <Show when={remote.isError}><Column width={1400} height={210} gap={12} color={palette.panel} borderRadius={16} padding={20} border={{ width: 1, color: palette.line }}><text skipFocus width={1360} height={70} fontSize={30} maxLines={2} color={palette.foreground}>{failureMessages[failureOf(remote.error)]}</text><Row width={1360} height={68} gap={16}><Button label="Try again" action={() => void remote.refetch()} /><Button label="Account" action={() => navigate('/settings/account')} /><Show when={failureOf(remote.error) === 'CHANGED'}><Button label="Check for update" action={() => void app.updates.check(destination().toLowerCase() + '.response-v1')} /><Button label="Notify Dylan" disabled={!app.host.reportUrl || app.updates.state.report === 'sending' || app.updates.state.report === 'sent'} action={() => void app.updates.report(destination().toUpperCase())} /></Show></Row></Column></Show>
    <Show when={(!remote.isEnabled || !remote.isPending) && !remote.isError && titles().length === 0}><text skipFocus width={1400} height={70} fontSize={32} color={palette.muted}>{destination() === 'search' && !query() ? 'Search for a film or TV show.' : 'No titles here yet.'}</text></Show>
    <Row width={1400} height={390} gap={20} scroll="none"><For each={visible()}>{(title, i) => <Poster title={title} id={titleKey(title)} action={() => open(title)} onFocus={() => setHighlighted(title)} />}</For></Row>
    <Row width={1400} height={68} gap={16}>
      <Show when={windowPage() > 0}><Button label="Previous titles" action={() => setParams({ window: windowPage() - 1 })} /></Show>
      <Show when={(windowPage() + 1) * 5 < titles().length}><Button label="More titles" action={() => setParams({ window: windowPage() + 1 })} /></Show>
      <Show when={!['home', 'list', 'continue'].includes(destination())}>
        <Show when={page() > 1}><Button label="Previous page" action={() => setParams({ page: page() - 1, window: 0 })} /></Show>
        <Show when={titles().length > 0}><Button label="Next page" disabled={remote.isFetching} action={() => setParams({ page: page() + 1, window: 0 })} /></Show>
      </Show>
      <Show when={delayed()}><text skipFocus width={300} height={60} fontSize={24} color={palette.muted}>Refreshing…</text></Show>
    </Row>
  </Column>;
}
