import { createEffect, createMemo, createSignal, on, onCleanup, Show, untrack, type JSX } from 'solid-js';
import { createInfiniteQuery, createQuery } from '@tanstack/solid-query';
import { useLocation, useNavigate, useSearchParams } from '@solidjs/router';
import { Column, Row, VirtualGrid } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Menu, palette, Poster, Status, restoreFocus, time } from '../../ui/components';
import { Overlay } from '../../ui/Overlay';
import { Keyboard, TextField, type FieldHandle } from '../../ui/Input';
import { failureOf, finished, titleKey, type Title, type Viewing } from '../../domain/models';
import { ContentError } from '../../ui/ContentError';
import type { ContentOperation } from '../../ui/content-recovery';
import { catalogueKey, distinctTitles, nextCataloguePage, resumeEpisode, resumeViewing, searchMode, type SearchMode } from './catalog-state';
import { activeScope } from '../../ui/focus';
import { narratePage } from '../../ui/narration';
import { CINEMETA_TTL, matchPlayableTitle, type DiscoveryTitle } from '../../services/cinemeta';

import { CatalogShelves, isDiscovery, shelfTitleKey as homeKey, type ShelfTitle as HomeTitle } from './CatalogShelves';
import { BrowseShelves } from './BrowseShelves';
type HomeShelf = { label: string; titles: HomeTitle[]; continuing?: boolean };
const genres = ['Action', 'Adventure', 'Animation', 'Biography', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Family', 'Fantasy', 'History', 'Horror', 'Mystery', 'Romance', 'Sci-Fi', 'Thriller', 'War', 'Western'];


export function Catalog() {
  const app = useApp(), location = useLocation(), navigate = useNavigate(); const [params, setParams] = useSearchParams();
  const destination = () => location.pathname.slice(1) || 'home', query = () => String(params.q || '').slice(0, 64), sort = () => String(params.sort || 'latest');
  const mode = () => searchMode(params.mode);
  const filterKey = () => JSON.stringify(catalogueKey(destination(), query(), sort(), app.profileId(), app.authRevision(), mode()));
  function changeSearch(value: string, next: SearchMode = mode(), replace = true) {
    if (value !== query() || next !== mode()) {
      app.browseOrigins.delete(JSON.stringify(catalogueKey('search', value, sort(), app.profileId(), app.authRevision(), next)));
    }
    setParams({ mode: next === 'anime' ? 'anime' : undefined, q: value || undefined, page: undefined }, { replace });
  }
  narratePage(() => destination() === 'search' ? 'Search' : destination() === 'home' ? 'Home' : destination() === 'shows' ? 'TV shows' : destination() === 'list' ? 'My List' : destination() === 'continue' ? 'Continue Watching' : destination() === 'anime' ? 'Anime' : 'Movies');
  const [sortMenu, setSortMenu] = createSignal(false);
  const home = createQuery(() => ({ queryKey: ['home', app.profileId(), app.authRevision()], enabled: destination() === 'home', queryFn: ({ signal }) => app.flixtor.home(signal), retry: 1 }));
  const popularMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'top'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'top', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const featuredMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'imdbRating'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'imdbRating', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const popularSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'top'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('series', 'top', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const featuredSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'imdbRating'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('series', 'imdbRating', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const categoryBrowse = () => ['movies', 'shows', 'anime'].includes(destination());
  const [genre, setGenre] = createSignal(''), [genreMenu, setGenreMenu] = createSignal(false);
  const genreMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'genre', genre()], enabled: destination() === 'home' && !!genre(), queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'top', genre(), signal), staleTime: CINEMETA_TTL, retry: false }));
  const genreSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'genre', genre()], enabled: destination() === 'home' && !!genre(), queryFn: ({ signal }) => app.cinemeta.catalog('series', 'top', genre(), signal), staleTime: CINEMETA_TTL, retry: false }));
  const remote = createInfiniteQuery(() => ({
    queryKey: catalogueKey(destination(), query(), sort(), app.profileId(), app.authRevision(), mode()),
    initialPageParam: 1, maxPages: 8,
    enabled: destination() === 'search' && !!query().trim(),
    queryFn: ({ pageParam, signal }) => mode() === 'anime' ? app.anime.browse(query(), pageParam, signal) : app.flixtor.search(query(), pageParam, signal),
    getNextPageParam: (last, pages, page) => nextCataloguePage(last, pages, page),
    retry: (count, error) => failureOf(error) === 'NETWORK' && count < 1,
  }));
  const library = () => app.data.libraries[app.profileId()];
  const continuing = () => library()?.viewing.filter(v => !finished(v)) || [];
  const discoveryQueries = () => [popularMovies, featuredMovies, popularSeries, featuredSeries];
  const discoveryLoading = () => discoveryQueries().some(query => query.isPending);
  const shelves = createMemo<HomeShelf[]>(() => {
    const resume: HomeShelf[] = continuing().length ? [{ label: 'Continue Watching', titles: distinctTitles([continuing().map(v => v.title)]), continuing: true }] : [];
    const curated: HomeShelf[] = [
      { label: 'Popular Movies', titles: popularMovies.data || [] },
      { label: 'Featured Movies', titles: featuredMovies.data || [] },
      { label: 'Popular Series', titles: popularSeries.data || [] },
      { label: 'Featured Series', titles: featuredSeries.data || [] },
      ...(genre() ? [{ label: `${genre()} Movies`, titles: genreMovies.data || [] }, { label: `${genre()} Series`, titles: genreSeries.data || [] }] : []),
    ].filter(shelf => shelf.titles.length);
    const fallback = !curated.length ? (home.data || []).map(shelf => ({ ...shelf })) : [];
    return [...resume, ...(curated.length ? curated : fallback)];
  });
  const titles = createMemo(() => destination() === 'list' ? library()?.favorites || [] : destination() === 'continue' ? distinctTitles([continuing().map(v => v.title)]) : distinctTitles(remote.data?.pages || []));
  const [highlighted, setHighlighted] = createSignal<HomeTitle>();
  const [availabilityMessage, setAvailabilityMessage] = createSignal('');
  const [checkingId, setCheckingId] = createSignal('');
  const [availabilityFailure, setAvailabilityFailure] = createSignal<ContentOperation>();
  let availabilityRequest: AbortController | undefined;
  let dwell: ReturnType<typeof setTimeout> | undefined;
  function highlight(title: HomeTitle) { clearTimeout(dwell); dwell = setTimeout(() => { setHighlighted(title); setAvailabilityMessage(''); }, 250); }
  createEffect(on(filterKey, () => { clearTimeout(dwell); setHighlighted(undefined); availabilityRequest?.abort(); setAvailabilityFailure(undefined); setAvailabilityMessage(''); setCheckingId(''); }));
  onCleanup(() => clearTimeout(dwell));
  onCleanup(() => availabilityRequest?.abort());
  const hero = () => highlighted() || shelves()[0]?.titles[0];
  const playableHero = () => { const value = hero(); return value && !isDiscovery(value) ? value : undefined; };
  const heroDetail = createQuery(() => ({ queryKey: ['detail', playableHero() ? titleKey(playableHero()!) : '', app.profileId(), app.authRevision()],
    enabled: destination() === 'home' && !!playableHero(), queryFn: ({ signal }) => playableHero()!.source === 'HIANIME' ? app.anime.detail(playableHero()!, signal) : app.flixtor.detail(playableHero()!, signal), retry: false }));
  createEffect(() => {
    const title = highlighted() || (destination() === 'home' ? heroDetail.data?.title || hero() : titles()[0]);
    app.setStage(title ? { poster: title.poster, backdrop: title.backdrop } : undefined);
  });
  const [delayed, setDelayed] = createSignal(false);
  createEffect(() => {
    const fetching = destination() === 'home' ? home.isFetching || discoveryQueries().some(query => query.isFetching) || genreMovies.isFetching || genreSeries.isFetching : remote.isFetching;
    if (!fetching) setDelayed(false);
    const timer = setTimeout(() => setDelayed(fetching), 120); onCleanup(() => clearTimeout(timer));
  });
  function open(title: Title, focus: string) {
    app.setSelected(title); app.titleCache.set(titleKey(title), title);
    if (app.titleCache.size > 256) app.titleCache.delete(app.titleCache.keys().next().value!);
    app.lastFocus = focus; navigate(`/detail?title=${encodeURIComponent(titleKey(title))}`);
  }
  async function openDiscovery(item: DiscoveryTitle, focus: string) {
    availabilityRequest?.abort();
    const request = new AbortController(); availabilityRequest = request;
    setAvailabilityFailure(undefined); setCheckingId(homeKey(item)); setAvailabilityMessage('Checking FlixTor availability…');
    try {
      const candidates = await app.flixtor.search(item.name, 1, request.signal);
      if (request.signal.aborted) return;
      const match = matchPlayableTitle(item, candidates);
      if (match) {
        setAvailabilityMessage('');
        open({ ...match, poster: match.poster || item.poster, backdrop: match.backdrop || item.backdrop, synopsis: match.synopsis || item.synopsis }, focus);
      }
      else setAvailabilityMessage(`${item.name} is not available on FlixTor.`);
    } catch (error) {
      if (!request.signal.aborted) {
        setAvailabilityMessage('');
        setAvailabilityFailure({ id: 'availability', error, retry: () => openDiscovery(item, focus) });
      }
    } finally {
      if (availabilityRequest === request) { availabilityRequest = undefined; setCheckingId(''); }
    }
  }
  function chooseHomeTitle(item: HomeTitle, focus: string) {
    if (isDiscovery(item)) void app.run(() => openDiscovery(item, focus));
    else open(item, focus);
  }
  function remember(rail: number, item: number, focus: string, shelf?: string, title?: string) {
    const previous = app.browseOrigins.get(filterKey());
    const positions = { ...previous?.positions };
    if (shelf && title) positions[shelf] = { title, item };
    app.browseOrigins.set(filterKey(), { rail, item, focus, shelf, title, positions });
    if (app.browseOrigins.size > 32) app.browseOrigins.delete(app.browseOrigins.keys().next().value!);
  }
  const pending = () => destination() === 'home' ? home.isPending : remote.isEnabled && remote.isPending;
  const error = () => destination() === 'home' ? home.error : remote.error;
  const retry = () => destination() === 'home' ? home.refetch() : remote.isFetchNextPageError ? remote.fetchNextPage() : remote.refetch();
  const recoveryOperations = (): ContentOperation[] => {
    if (destination() === 'search') return [{ id: 'search', error: remote.error, fetching: remote.isFetching, retry }];
    if (destination() !== 'home') return [];
    const discovery = [...discoveryQueries(), ...(genre() ? [genreMovies, genreSeries] : [])];
    const usable = shelves().some(shelf => !shelf.continuing && shelf.titles.length);
    return [
      ...(availabilityFailure() ? [availabilityFailure()!] : []),
      ...(!usable ? [{ id: 'home', error: home.error, fetching: home.isFetching, retry: () => home.refetch() },
        ...discovery.map((query, index) => ({ id: `discovery-${index}`, error: query.error, fetching: query.isFetching, retry: () => query.refetch() }))] : []),
    ];
  };
  const loadMore = () => { if (remote.hasNextPage && !remote.isFetching && !remote.isFetchNextPageError) void remote.fetchNextPage(); };
  return <>
    <Show when={destination() === 'search' ? `search:${app.profileId()}:${app.authRevision()}` : filterKey()} keyed>{layoutKey => {
      const key = filterKey();
      // Route changes replace this keyed owner. Do not also swap its old subtree
      // during disposal: SolidTV would retain scene nodes whose native parents died.
      const homeLayout = untrack(destination) === 'home', browseLayout = untrack(categoryBrowse);
      const origin = untrack(() => app.browseOrigins.get(key));
      const focusId = (rail: number, title: HomeTitle) => `${key}:${rail}:${homeKey(title)}`;
      const shelfFocus = (shelf: string, title: HomeTitle, index: number, rail: number, id: string) => {
        remember(rail, index, id, shelf, homeKey(title)); highlight(title);
      };
      const heading = () => (
          <Row width={1728} height={82} gap={24} skipFocus={!['movies', 'shows'].includes(destination())}
            onUp={() => restoreFocus(`nav-${destination() === 'continue' ? 'home' : destination()}`)}>
            <text skipFocus width={1100} height={76} fontSize={54} fontFamily="Roboto700" maxLines={1}>{destination() === 'shows' ? 'TV shows' : destination() === 'list' ? 'My List' : destination() === 'continue' ? 'Continue Watching' : destination() === 'anime' ? 'Anime' : 'Movies'}</text>
            <Show when={['movies', 'shows'].includes(destination())}><Button label={sort() === 'best' ? 'Top rated' : sort() === 'name' ? 'A–Z' : 'Latest'} width={220} action={() => setSortMenu(true)} /></Show>
            <Show when={delayed() && destination() !== 'search'}><text skipFocus width={280} height={54} fontSize={24} color={palette.muted}>{remote.isFetchingNextPage ? 'Loading more…' : 'Refreshing…'}</text></Show>
          </Row>
      );
      return <Show when={homeLayout} fallback={
        <Column width={1728} height={848} gap={24} scroll="none">
          <Show when={!browseLayout && destination() !== 'search' && !titles().length}>{heading()}</Show>
          <Show when={browseLayout} fallback={<>
          <Show when={destination() === 'search'} fallback={<>
            <Show when={pending()}><Status message="Loading catalogue…" /></Show>
            <Show when={!pending() && !titles().length && !error()}><Column width={1500} height={240} gap={24}>
              <Status message={destination() === 'list' ? 'Save the stories you want to watch. They’ll be here when you’re ready.' : destination() === 'continue' ? 'Your next watch starts here.' : 'No titles found.'} />
              <Button label="Browse movies" autofocus action={() => navigate('/movies')} />
            </Column></Show>
            <Show when={titles().length}><TitleGrid header={heading()} up={() => restoreFocus(`nav-${destination() === 'continue' ? 'home' : destination()}`)} titles={titles()} prefix={key} columns={6} width={1728} height={848} initial={origin?.item || 0}
              viewing={continuing()} focus={(title, index) => { remember(0, index, focusId(0, title)); highlight(title); }} open={title => open(title, focusId(0, title))} end={loadMore} /></Show>
          </>}>
            <SearchResults mode={mode()} switchMode={(next, value) => changeSearch(value, next, false)}
              query={query()} change={value => changeSearch(value)} titles={titles()} pending={pending()} error={error()} retry={retry} prefix={filterKey()}
              initial={app.browseOrigins.get(filterKey())?.item || 0} restore={!!app.browseOrigins.get(filterKey())} focus={(title, index) => remember(0, index, `${filterKey()}:0:${titleKey(title)}`)} open={title => open(title, `${filterKey()}:0:${titleKey(title)}`)} end={loadMore} />
          </Show>

          </>}>
            <BrowseShelves header={heading()} destination={destination()} sort={sort()} query={query()} prefix={key} origin={origin}
              viewing={continuing()} focus={shelfFocus} open={open} />
          </Show>
        </Column>
      }>
        <CatalogShelves shelves={shelves().map(shelf => ({ ...shelf, id: shelf.label, viewAll: () => navigate('/continue') }))}
          prefix={key} height={848} origin={origin} viewing={continuing()} focus={shelfFocus} open={chooseHomeTitle} header={<>
          <Column width={1728} height={availabilityMessage() ? 560 : 456} gap={18} scroll="none">
            <Show when={hero()} fallback={<text skipFocus width={950} height={150} fontSize={76} fontFamily="Roboto700">Welcome, {app.profile()?.name || 'FlixTor'}</text>}>{selectedHero => {
              const current = selectedHero() as HomeTitle;
              const external = isDiscovery(current) ? current : undefined;
              const playable = external ? undefined : current as Title;
              return <>
              <text skipFocus width={950} height={160} fontSize={76} lineHeight={76} fontFamily="Roboto700" maxLines={2}>{current.name}</text>
              <text skipFocus width={920} height={38} fontSize={26} color={palette.muted} maxLines={1}>{[
                current.year,
                external ? external.genres.slice(0, 2).join(' · ') : current.genres,
                external ? external.type === 'series' ? 'TV Series' : 'Movie' : playable?.source === 'HIANIME' ? 'Anime' : '',
              ].filter(Boolean).join('   ·   ')}</text>
              <text skipFocus width={850} height={105} fontSize={28} color={palette.muted} maxLines={3}>{external ? external.synopsis || 'Discover a story to watch.' : heroDetail.data?.title.synopsis || playable?.synopsis || 'Settle in. Find something worth watching.'}</text>
              <Row width={1420} height={72} gap={18} onUp={() => restoreFocus('nav-home')}>
                <Show when={external} fallback={<>
                  <Button id="hero-play" label={resumeViewing(continuing(), playable!) ? 'Resume' : 'Play'} icon="play" primary autofocus={!origin} width={220} disabled={!heroDetail.data} action={() => app.run(async () => {
                    const detail = heroDetail.data; if (!detail) return;
                    const viewing = resumeViewing(continuing(), detail.title); navigate('/player'); await app.playback!.start(detail, resumeEpisode(detail, viewing), viewing?.version || undefined);
                  })} />
                  <Button id="hero-details" label="Details" width={220} action={() => open(playable!, focusId(0, playable!))} />
                </>}>
                  {item => <Button id="hero-find" label={checkingId() === homeKey(item()) ? 'Checking…' : 'Find on FlixTor'} icon="play" primary autofocus={!origin} width={290} disabled={!!checkingId()} action={() => void app.run(() => openDiscovery(item(), focusId(0, item())))} />}
                </Show>
                <Button label={genre() ? `Genre: ${genre()}` : 'Browse genres'} icon="film" width={290} action={() => setGenreMenu(true)} />
                <Show when={home.isError}><Button label="Refresh catalogue" action={() => void home.refetch()} /></Show>
              </Row>
              <Show when={availabilityMessage()}><Status width={1000} message={availabilityMessage()} error={!checkingId()} /></Show>
            </>;
            }}</Show>
          </Column>
          <Show when={!shelves().length && (home.isPending || discoveryLoading())}><Status message="Finding something to watch…" /></Show>
        </>} />

      </Show>;
    }}</Show>
    <ContentError screenKey={filterKey()} operations={recoveryOperations()} />
    <Show when={sortMenu()}><Overlay title="Sort titles" close={() => setSortMenu(false)} width={700} height={510}><Menu width={620} height={438} title="Sort titles" options={[
      ['latest', 'Latest'], ['best', 'Top rated'], ['name', 'A–Z'],
    ].map(([value, label]) => ({ id: value, label, selected: sort() === value, action: () => { setSortMenu(false); setParams({ sort: value, page: undefined, window: undefined }); } }))} /></Overlay></Show>
    <Show when={genreMenu()}><Overlay title="Discover by genre" close={() => setGenreMenu(false)} width={760} height={740}><Menu width={680} height={660} title="Discover by genre" back={() => setGenreMenu(false)} options={[
      { id: 'all', label: 'All genres', selected: !genre(), action: () => { setGenre(''); setGenreMenu(false); } },
      ...genres.map(value => ({ id: value, label: value, selected: genre() === value, action: () => { setGenre(value); setGenreMenu(false); } })),
    ]} /></Overlay></Show>
  </>;
}

function TitleGrid(props: { header?: JSX.Element; up?(): boolean; titles: Title[]; prefix: string; columns: number; width: number; height: number; initial?: number; autofocus?: boolean; viewing?: Viewing[]; focus?(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const cardWidth = () => (props.width - (props.columns - 1) * 28 - 16) / props.columns;
  const rowGap = 28;
  const rowHeight = () => cardWidth() * 1.5 + 78;
  const visibleRows = () => Math.max(1, Math.floor((props.height - 20 + rowGap) / (rowHeight() + rowGap)));
  // The heading occupies a virtual row so one owner scrolls headings and media.
  const headings = Array.from({ length: props.columns }, (_, column) => ({ heading: true as const, column }));
  const headerCount = () => props.header ? props.columns : 0;
  const items = createMemo(() => props.header ? [...headings, ...props.titles] : props.titles);
  const [focusedIndex, setFocusedIndex] = createSignal(props.initial || 0);
  const viewportHeight = () => props.header ? props.height : visibleRows() * rowHeight() + (visibleRows() - 1) * rowGap + 20;
  return <view width={props.width} height={viewportHeight()} clipping={[20, 20, 20, 20]} forwardFocus={0}>
    <VirtualGrid each={items()} x={8} y={10} width={props.width - 16} height={viewportHeight() - 20} gap={rowGap} columns={props.columns} rows={visibleRows()} buffer={1} scroll="always" offset={10} selected={focusedIndex() + headerCount()} wrap={false}
      onUp={() => !!props.header && focusedIndex() < props.columns && !!props.up?.()}
      onEndReachedThreshold={props.columns * 2} onEndReached={props.end}>
      {item => <Show when={'heading' in item() ? undefined : item() as Title} fallback={
        <view skipFocus width={cardWidth()} height={82}>{(item() as typeof headings[number]).column === 0 ? props.header : undefined}</view>
      }>{title => {
        const index = () => props.titles.findIndex(value => titleKey(value) === titleKey(title()));
        return <Poster title={title()} width={cardWidth()} id={`${props.prefix}:0:${titleKey(title())}`} viewing={props.viewing && resumeViewing(props.viewing, title())}
          autofocus={props.autofocus !== false && (props.initial || 0) === index()} action={() => props.open(title())}
          onFocus={() => { const selected = index(); setFocusedIndex(selected); props.focus?.(title(), selected); if (selected >= props.titles.length - props.columns * 2) props.end(); }} />;
      }}</Show>}
    </VirtualGrid>
  </view>;
}
function SearchResults(props: { mode: SearchMode; switchMode(mode: SearchMode, query: string): void; query: string; change(value: string): void; titles: Title[]; pending: boolean; error: unknown; retry(): void; prefix: string; initial: number; restore: boolean; focus(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const app = useApp(); const [draft, setDraft] = createSignal(props.query);
  let field: FieldHandle | undefined, timer: ReturnType<typeof setTimeout> | undefined, keyboardFocus = 'search-0-0';
  createEffect(on(() => [props.query, props.mode] as const, ([value]) => { clearTimeout(timer); setDraft(value); }));
  const change = (value: string) => { setDraft(value); clearTimeout(timer); timer = setTimeout(() => props.change(value.trim().slice(0, 64)), 300); };
  const label = () => props.mode === 'anime' ? 'Search anime' : 'Search films and TV shows';
  const switchMode = (mode: SearchMode) => { if (mode === props.mode) return; clearTimeout(timer); props.switchMode(mode, draft().trim().slice(0, 64)); };
  const done = () => { clearTimeout(timer); props.change(draft().trim().slice(0, 64)); if (props.titles[0]) restoreFocus(`${props.prefix}:0:${titleKey(props.titles[0])}`); };
  let alive = true;
  const keydown = (event: KeyboardEvent) => {
    if (event.target instanceof HTMLInputElement || event.ctrlKey || event.metaKey || event.altKey || event.isComposing || document.querySelector('dialog[open]') || app.inputRequest() || activeScope() !== 'screen') return;
    if (event.key.length === 1 || event.key === 'Backspace') { event.preventDefault(); event.stopImmediatePropagation(); field?.edit(event.key === 'Backspace' ? 'Delete' : event.key); }
  };
  window.addEventListener('keydown', keydown, true);
  onCleanup(() => { alive = false; clearTimeout(timer); window.removeEventListener('keydown', keydown, true); });
  return <Column width={1728} height={848} gap={24} scroll="none">
    <Row width={1728} height={82} gap={24} onUp={() => restoreFocus('nav-search')}>
      <text skipFocus width={760} height={76} fontSize={54} lineHeight={64} fontFamily="Roboto700" maxLines={1}>Find your next story.</text>
      <Button id="search-mode-media" label="Movies & TV" width={280} selected={props.mode === 'media'} action={() => switchMode('media')} />
      <Button id="search-mode-anime" label="Anime" width={180} selected={props.mode === 'anime'} action={() => switchMode('anime')} />
      <Button label="Device keyboard" width={360} action={() => app.run(async () => { const value = await app.platform.call<string | null>('input', { label: label(), value: draft() }); if (alive && value !== null) change(value); })} />
    </Row>
    <TextField id="search-field" label={label()} value={draft()} change={change} width={1728} maxLength={64} handle={handle => field = handle} up={() => restoreFocus(`search-mode-${props.mode}`)} down={() => restoreFocus('search-0-0')} submit={done} />
    <view width={1728} height={620} clipping={[0, 20, 20, 20]} forwardFocus={0}>
    <Row width={1728} height={620} gap={50}>
      <Column width={580} height={620} gap={12} scroll="none">
        <Keyboard width={580} prefix="search" autofocus={!props.restore} leave={() => field?.focus()} doneLabel="View results" edit={key => { if (field) field.edit(key); else change(key === 'Delete' ? draft().slice(0, -1) : key === 'Clear' ? '' : draft() + (key === 'Space' ? ' ' : key)); }} done={done}
          keyFocus={id => { keyboardFocus = id; }} />
      </Column>
      <Column width={1098} height={620} gap={16} scroll="none" onLeft={() => restoreFocus(keyboardFocus)}>
        <Show when={props.pending}><Status width={1098} message="Searching…" /></Show>
        <Show when={!props.query}><Status width={1098} message={props.mode === 'anime' ? 'Search for anime. Type, or use the keyboard.' : 'Search for a film or TV show. Type, or use the keyboard.'} /></Show>
        <Show when={props.query && !props.pending && !props.error && !props.titles.length}><Status width={1098} message={`No results for “${props.query}”. Try a different title.`} /></Show>
        <Show when={props.prefix} keyed>{prefix => <Show when={props.titles.length}><TitleGrid titles={props.titles} prefix={prefix} columns={4} width={1098} height={600} initial={props.initial} autofocus={props.restore} focus={props.focus} open={props.open} end={props.end} /></Show>}</Show>
      </Column>
    </Row>
    </view>
  </Column>;
}
