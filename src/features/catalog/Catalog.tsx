import { createEffect, createMemo, createSignal, on, onCleanup, onMount, Show, For, untrack } from 'solid-js';
import { createInfiniteQuery, createQuery } from '@tanstack/solid-query';
import { useLocation, useNavigate, useSearchParams } from '@solidjs/router';
import { Column, Row, VirtualGrid, VirtualRow } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, EdgeFade, Menu, palette, Poster, Status, restoreFocus, time } from '../../ui/components';
import { Overlay } from '../../ui/Overlay';
import { Keyboard, TextField, type FieldHandle } from '../../ui/Input';
import { failureOf, finished, titleKey, type Title, type Viewing } from '../../domain/models';
import { failureMessages } from '../../ui/copy';
import { catalogueKey, distinctTitles, nextCataloguePage, resumeEpisode, resumeViewing } from './catalog-state';
import { narratePage } from '../../ui/narration';
import { CINEMETA_TTL, matchPlayableTitle, type DiscoveryTitle } from '../../services/cinemeta';

type HomeTitle = Title | DiscoveryTitle;
type HomeShelf = { label: string; titles: HomeTitle[]; continuing?: boolean };
const genres = ['Action', 'Adventure', 'Animation', 'Biography', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Family', 'Fantasy', 'History', 'Horror', 'Mystery', 'Romance', 'Sci-Fi', 'Thriller', 'War', 'Western'];
const isDiscovery = (title: HomeTitle): title is DiscoveryTitle => 'type' in title && (title.type === 'movie' || title.type === 'series');
const homeKey = (title: HomeTitle) => isDiscovery(title) ? `cinemeta:${title.type}:${title.id}` : titleKey(title);

export function Catalog() {
  const app = useApp(), location = useLocation(), navigate = useNavigate(); const [params, setParams] = useSearchParams();
  const destination = () => location.pathname.slice(1) || 'home', query = () => String(params.q || '').slice(0, 64), sort = () => String(params.sort || 'latest');
  const filterKey = () => JSON.stringify(catalogueKey(destination(), query(), sort(), app.profileId(), app.authRevision()));
  narratePage(() => destination() === 'search' ? 'Search' : destination() === 'home' ? 'Home' : destination() === 'shows' ? 'TV shows' : destination() === 'list' ? 'My List' : destination() === 'continue' ? 'Continue Watching' : destination() === 'anime' ? 'Anime' : 'Movies');
  const [sortMenu, setSortMenu] = createSignal(false);
  const home = createQuery(() => ({ queryKey: ['home', app.profileId(), app.authRevision()], enabled: destination() === 'home', queryFn: ({ signal }) => app.flixtor.home(signal), retry: 1 }));
  const popularMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'top'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'top', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const featuredMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'imdbRating'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'imdbRating', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const popularSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'top'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('series', 'top', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const featuredSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'imdbRating'], enabled: destination() === 'home', queryFn: ({ signal }) => app.cinemeta.catalog('series', 'imdbRating', undefined, signal), staleTime: CINEMETA_TTL, retry: false }));
  const [genre, setGenre] = createSignal(''), [genreMenu, setGenreMenu] = createSignal(false);
  const genreMovies = createQuery(() => ({ queryKey: ['discovery', 'movie', 'genre', genre()], enabled: destination() === 'home' && !!genre(), queryFn: ({ signal }) => app.cinemeta.catalog('movie', 'top', genre(), signal), staleTime: CINEMETA_TTL, retry: false }));
  const genreSeries = createQuery(() => ({ queryKey: ['discovery', 'series', 'genre', genre()], enabled: destination() === 'home' && !!genre(), queryFn: ({ signal }) => app.cinemeta.catalog('series', 'top', genre(), signal), staleTime: CINEMETA_TTL, retry: false }));
  const remote = createInfiniteQuery(() => ({
    queryKey: catalogueKey(destination(), query(), sort(), app.profileId(), app.authRevision()),
    initialPageParam: 1, maxPages: 8,
    enabled: !['home', 'list', 'continue'].includes(destination()) && (destination() !== 'search' || !!query().trim()),
    queryFn: ({ pageParam, signal }) => destination() === 'anime' ? app.anime.browse(query(), pageParam, signal) : destination() === 'search' ? app.flixtor.search(query(), pageParam, signal) : app.flixtor.browse(destination() === 'shows' ? 'tvshows' : 'movies', pageParam, sort(), signal),
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
  let availabilityRequest: AbortController | undefined;
  let dwell: ReturnType<typeof setTimeout> | undefined;
  function highlight(title: HomeTitle) { clearTimeout(dwell); dwell = setTimeout(() => { setHighlighted(title); setAvailabilityMessage(''); }, 250); }
  createEffect(on(filterKey, () => { clearTimeout(dwell); setHighlighted(undefined); }));
  onCleanup(() => clearTimeout(dwell));
  onCleanup(() => availabilityRequest?.abort());
  const hero = () => highlighted() || shelves()[0]?.titles[0];
  const playableHero = () => { const value = hero(); return value && !isDiscovery(value) ? value : undefined; };
  const heroDetail = createQuery(() => ({ queryKey: ['detail', playableHero() ? titleKey(playableHero()!) : '', app.profileId(), app.authRevision()],
    enabled: destination() === 'home' && !!playableHero(), queryFn: ({ signal }) => playableHero()!.source === 'HIANIME' ? app.anime.detail(playableHero()!, signal) : app.flixtor.detail(playableHero()!, signal), retry: false }));
  createEffect(() => {
    if (destination() === 'home') {
      const title = heroDetail.data?.title || hero();
      app.setStage(title ? { poster: title.poster, backdrop: title.backdrop } : undefined);
    }
    else app.setStage(undefined);
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
    setCheckingId(homeKey(item)); setAvailabilityMessage('Checking FlixTor availability…');
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
      if (!request.signal.aborted) setAvailabilityMessage(`${item.name} could not be checked. Try again.`);
    } finally {
      if (availabilityRequest === request) { availabilityRequest = undefined; setCheckingId(''); }
    }
  }
  function chooseHomeTitle(item: HomeTitle, focus: string) {
    if (isDiscovery(item)) void app.run(() => openDiscovery(item, focus));
    else open(item, focus);
  }
  function remember(rail: number, item: number, focus: string) {
    app.browseOrigins.set(filterKey(), { rail, item, focus });
    if (app.browseOrigins.size > 32) app.browseOrigins.delete(app.browseOrigins.keys().next().value!);
  }
  const pending = () => destination() === 'home' ? home.isPending : remote.isEnabled && remote.isPending;
  const error = () => destination() === 'home' ? home.error : remote.error;
  const retry = () => destination() === 'home' ? void home.refetch() : remote.isFetchNextPageError ? void remote.fetchNextPage() : void remote.refetch();
  const loadMore = () => { if (remote.hasNextPage && !remote.isFetching && !remote.isFetchNextPageError) void remote.fetchNextPage(); };
  return <>
    <Show when={destination() === 'search' ? `search:${app.profileId()}:${app.authRevision()}` : filterKey()} keyed>{layoutKey => {
      const key = filterKey();
      const origin = untrack(() => app.browseOrigins.get(key));
      const focusId = (rail: number, title: HomeTitle) => `${key}:${rail}:${homeKey(title)}`;
      return <Show when={destination() === 'home'} fallback={
        <Column width={1728} height={848} gap={24} scroll="none">
          <Row width={1728} height={82} gap={24}>
            <text skipFocus width={destination() === 'search' ? 1728 : 1100} height={76} fontSize={54} fontFamily="Roboto700">{destination() === 'shows' ? 'TV shows' : destination() === 'list' ? 'My List' : destination() === 'continue' ? 'Continue Watching' : destination() === 'search' ? 'Find your next story.' : destination() === 'anime' ? 'Anime' : 'Movies'}</text>
            <Show when={['movies', 'shows'].includes(destination())}><Button label={sort() === 'best' ? 'Top rated' : sort() === 'name' ? 'A–Z' : 'Latest'} width={220} action={() => setSortMenu(true)} /></Show>
            <Show when={destination() === 'anime'}><Button label="Search anime" icon="search" width={280} action={() => app.run(async () => { const value = await app.input('Search anime', query()); if (value !== null) setParams({ q: value, page: undefined }); })} /></Show>
            <Show when={delayed() && destination() !== 'search'}><text skipFocus width={280} height={54} fontSize={24} color={palette.muted}>{remote.isFetchingNextPage ? 'Loading more…' : 'Refreshing…'}</text></Show>
          </Row>
          <Show when={destination() === 'search'} fallback={<>
            <Show when={pending()}><Status message="Loading catalogue…" /></Show>
            <Show when={!pending() && !titles().length && !error()}><Column width={1500} height={240} gap={24}>
              <Status message={destination() === 'list' ? 'Save the stories you want to watch. They’ll be here when you’re ready.' : destination() === 'continue' ? 'Your next watch starts here.' : 'No titles found.'} />
              <Button label="Browse movies" autofocus action={() => navigate('/movies')} />
            </Column></Show>
            <Show when={titles().length}><TitleGrid titles={titles()} prefix={key} columns={6} width={1728} height={error() ? 530 : 650} initial={origin?.item || 0}
              viewing={continuing()} focus={(title, index) => { remember(0, index, focusId(0, title)); highlight(title); }} open={title => open(title, focusId(0, title))} end={loadMore} /></Show>
          </>}>
            <SearchResults query={query()} change={value => setParams({ q: value || undefined, page: undefined }, { replace: true })} titles={titles()} pending={pending()} error={error()} retry={retry} prefix={filterKey()}
              initial={origin?.item || 0} restore={!!origin} focus={(title, index) => remember(0, index, `${filterKey()}:0:${titleKey(title)}`)} open={title => open(title, `${filterKey()}:0:${titleKey(title)}`)} end={loadMore} />
          </Show>
          <Show when={error() && destination() !== 'search'}><Row width={1728} height={86} gap={24}><text skipFocus width={1100} height={80} fontSize={26} maxLines={2} color={palette.danger}>{failureMessages[failureOf(error())]}</text><Button label="Try again" action={retry} /><Button label="Account" width={220} action={() => navigate('/settings/account')} /></Row></Show>
        </Column>
      }>
        <Column width={1728} height={848} gap={30} scroll="edge" selected={origin ? origin.rail + 1 : 0}>
          <Column width={1728} height={440} gap={18} scroll="none">
            <Show when={hero()} fallback={<text skipFocus width={950} height={150} fontSize={76} fontFamily="Roboto700">Welcome, {app.profile()?.name || 'FlixTor'}</text>}>{selectedHero => {
              const current = selectedHero() as HomeTitle;
              const external = isDiscovery(current) ? current : undefined;
              const playable = external ? undefined : current as Title;
              return <>
              <text skipFocus width={950} height={150} fontSize={76} fontFamily="Roboto700" maxLines={2}>{current.name}</text>
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
          <Show when={!discoveryLoading() && !discoveryQueries().some(query => query.data?.length) && home.isError}><Status message="Discovery is unavailable. Try again or browse Movies and TV Shows." error /></Show>
          <Show when={home.isError && !shelves().length}><Button label="Retry FlixTor catalogue" autofocus action={() => void home.refetch()} /></Show>
          <For each={shelves()}>{(shelf, rail) => {
            const visible = shelf.continuing ? 4 : 6;
            const railHeight = shelf.continuing ? 320 : 480;
            const [position, setPosition] = createSignal(origin?.rail === rail() ? origin.item : 0);
            return <Column width={1728} height={railHeight + 72} gap={24} scroll="none">
            <Row width={1728} height={48} gap={24} skipFocus={!shelf.continuing}><text skipFocus width={1250} height={48} fontSize={36} fontFamily="Roboto700">{shelf.label}</text>
              <Show when={shelf.continuing}><Button label="View Continue Watching" height={48} width={370} quiet action={() => navigate('/continue')} /></Show>
            </Row>
            <view width={1728} height={railHeight} clipping forwardFocus={0}>
              <VirtualRow each={shelf.titles} x={8} width={1712} height={railHeight} gap={28} displaySize={visible} bufferSize={2} scroll="edge" wrap={false}
                onLeft={function (this: { selected?: number }) { return this.selected === 0; }}
                onRight={function (this: { selected?: number }) { return this.selected !== undefined && this.selected >= shelf.titles.length - 1; }}
                selected={origin?.rail === rail() ? origin.item : 0}>
                {title => <Poster title={title()} width={shelf.continuing ? 400 : 260} landscape={shelf.continuing}
                viewing={shelf.continuing && !isDiscovery(title()) ? resumeViewing(continuing(), title() as Title) : undefined} id={focusId(rail(), title())}
                autofocus={origin?.focus === focusId(rail(), title())} action={() => chooseHomeTitle(title(), focusId(rail(), title()))}
                onFocus={() => { const item = shelf.titles.findIndex(t => homeKey(t) === homeKey(title())); setPosition(item); remember(rail(), item, focusId(rail(), title())); highlight(title()); }} />}
            </VirtualRow>
              <Show when={position() >= visible}><EdgeFade side="left" width={56} height={railHeight} /></Show>
              <Show when={position() + visible < shelf.titles.length}><EdgeFade side="right" width={56} height={railHeight} /></Show>
            </view>
          </Column>;
          }}</For>
        </Column>
      </Show>;
    }}</Show>
    <Show when={sortMenu()}><Overlay title="Sort titles" close={() => setSortMenu(false)} width={700} height={510}><Menu width={620} height={438} title="Sort titles" options={[
      ['latest', 'Latest'], ['best', 'Top rated'], ['name', 'A–Z'],
    ].map(([value, label]) => ({ id: value, label, selected: sort() === value, action: () => { setSortMenu(false); setParams({ sort: value, page: undefined, window: undefined }); } }))} /></Overlay></Show>
    <Show when={genreMenu()}><Overlay title="Discover by genre" close={() => setGenreMenu(false)} width={760} height={740}><Menu width={680} height={660} title="Discover by genre" back={() => setGenreMenu(false)} options={[
      { id: 'all', label: 'All genres', selected: !genre(), action: () => { setGenre(''); setGenreMenu(false); } },
      ...genres.map(value => ({ id: value, label: value, selected: genre() === value, action: () => { setGenre(value); setGenreMenu(false); } })),
    ]} /></Overlay></Show>
  </>;
}

function TitleGrid(props: { titles: Title[]; prefix: string; columns: number; width: number; height: number; initial?: number; autofocus?: boolean; viewing?: Viewing[]; focus?(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const cardWidth = () => (props.width - (props.columns - 1) * 28 - 16) / props.columns;
  const rowGap = 28;
  const rowHeight = () => cardWidth() * 1.5 + 78;
  const visibleRows = () => Math.max(1, Math.floor((props.height - 20 + rowGap) / (rowHeight() + rowGap)));
  const viewportHeight = () => visibleRows() * rowHeight() + (visibleRows() - 1) * rowGap + 20;
  return <view width={props.width} height={viewportHeight()} clipping forwardFocus={0}>
    <VirtualGrid each={props.titles} x={8} y={10} width={props.width - 16} height={viewportHeight() - 20} gap={rowGap} columns={props.columns} rows={visibleRows()} buffer={1} scroll="edge" selected={props.initial || 0} wrap={false}
      onEndReachedThreshold={props.columns * 2} onEndReached={props.end}>
      {(title, index) => <Poster title={title()} width={cardWidth()} id={`${props.prefix}:0:${titleKey(title())}`} viewing={props.viewing && resumeViewing(props.viewing, title())}
        autofocus={props.autofocus !== false && (props.initial || 0) === index()} action={() => props.open(title())}
        onFocus={() => { const item = title(), itemIndex = index(); props.focus?.(item, itemIndex); if (itemIndex >= props.titles.length - props.columns * 2) props.end(); }} />}
    </VirtualGrid>
  </view>;
}
function SearchResults(props: { query: string; change(value: string): void; titles: Title[]; pending: boolean; error: unknown; retry(): void; prefix: string; initial: number; restore: boolean; focus(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const app = useApp(); const [draft, setDraft] = createSignal(props.query), [composing, setComposing] = createSignal(false);
  let field: FieldHandle | undefined, timer: ReturnType<typeof setTimeout> | undefined, keyboardFocus = 'search-0-0';
  createEffect(on(() => props.query, value => setDraft(value)));
  const change = (value: string) => { setDraft(value); clearTimeout(timer); timer = setTimeout(() => props.change(value.trim().slice(0, 64)), 300); };
  const done = () => { clearTimeout(timer); props.change(draft().trim().slice(0, 64)); if (props.titles[0]) restoreFocus(`${props.prefix}:0:${titleKey(props.titles[0])}`); };
  let alive = true;
  const keydown = (event: KeyboardEvent) => {
    if (event.target instanceof HTMLInputElement || event.ctrlKey || event.metaKey || event.altKey || event.isComposing || document.querySelector('dialog[open]') || app.inputRequest()) return;
    if (event.key.length === 1 || event.key === 'Backspace') { event.preventDefault(); event.stopImmediatePropagation(); field?.edit(event.key === 'Backspace' ? 'Delete' : event.key); }
  };
  window.addEventListener('keydown', keydown, true);
  onCleanup(() => { alive = false; clearTimeout(timer); window.removeEventListener('keydown', keydown, true); });
  return <Column width={1728} height={720} gap={24} scroll="none">
    <TextField id="search-field" label="Search films and TV shows" value={draft()} change={change} width={1728} maxLength={64} handle={handle => field = handle} down={() => restoreFocus('search-0-0')} submit={done} />
    <Row width={1728} height={620} gap={50}>
      <Column width={580} height={620} gap={12} scroll="none">
        <Keyboard width={580} prefix="search" autofocus={!props.restore} edit={key => { if (field) field.edit(key); else change(key === 'Delete' ? draft().slice(0, -1) : key === 'Clear' ? '' : draft() + (key === 'Space' ? ' ' : key)); }} done={done}
          keyFocus={id => { keyboardFocus = id; }}
          right={() => props.titles[0] ? restoreFocus(`${props.prefix}:0:${titleKey(props.titles[0])}`) : false} />
        <Button label="Device keyboard" width={300} action={() => app.run(async () => { const value = await app.platform.call<string | null>('input', { label: 'Search films and TV shows', value: draft() }); if (alive && value !== null) change(value); })} />
      </Column>
      <Column width={1098} height={620} gap={16} scroll="none" onLeft={() => restoreFocus(keyboardFocus)}>
        <Show when={props.error}><Status width={1098} message={failureMessages[failureOf(props.error)]} error /><Button label="Try again" action={props.retry} /></Show>
        <Show when={props.pending}><Status width={1098} message="Searching…" /></Show>
        <Show when={!props.query}><Status width={1098} message="Search for a film or TV show. Type, or use the keyboard." /></Show>
        <Show when={props.query && !props.pending && !props.error && !props.titles.length}><Status width={1098} message={`No results for “${props.query}”. Try a different title.`} /></Show>
        <Show when={props.titles.length}><TitleGrid titles={props.titles} prefix={props.prefix} columns={4} width={1098} height={600} initial={props.initial} autofocus={props.restore} focus={props.focus} open={props.open} end={props.end} /></Show>
      </Column>
    </Row>
  </Column>;
}
