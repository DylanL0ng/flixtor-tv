import { createEffect, createMemo, createSignal, on, onCleanup, onMount, Show, For, untrack } from 'solid-js';
import { createInfiniteQuery, createQuery } from '@tanstack/solid-query';
import { useLocation, useNavigate, useSearchParams } from '@solidjs/router';
import { Column, Row, VirtualColumn, VirtualRow } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Menu, palette, Poster, Status, restoreFocus, time } from '../../ui/components';
import { Overlay } from '../../ui/Overlay';
import { Keyboard, TextField, type FieldHandle } from '../../ui/Input';
import { failureOf, finished, titleKey, type Shelf, type Title, type Viewing } from '../../domain/models';
import { failureMessages } from '../../ui/copy';
import { catalogueKey, chunk, distinctTitles, nextCataloguePage, resumeEpisode, resumeViewing } from './catalog-state';
import { narratePage } from '../../ui/narration';

export function Catalog() {
  const app = useApp(), location = useLocation(), navigate = useNavigate(); const [params, setParams] = useSearchParams();
  const destination = () => location.pathname.slice(1) || 'home', query = () => String(params.q || '').slice(0, 64), sort = () => String(params.sort || 'latest');
  const filterKey = () => JSON.stringify(catalogueKey(destination(), query(), sort(), app.profileId(), app.authRevision()));
  narratePage(() => destination() === 'search' ? 'Search' : destination() === 'home' ? 'Home' : destination() === 'shows' ? 'TV shows' : destination() === 'list' ? 'My List' : destination() === 'continue' ? 'Continue Watching' : destination() === 'anime' ? 'Anime' : 'Movies');
  const [sortMenu, setSortMenu] = createSignal(false);
  const home = createQuery(() => ({ queryKey: ['home', app.profileId(), app.authRevision()], enabled: destination() === 'home', queryFn: ({ signal }) => app.flixtor.home(signal), retry: 1 }));
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
  const shelves = createMemo<Shelf[]>(() => [...(continuing().length ? [{ label: 'Continue Watching', titles: distinctTitles([continuing().map(v => v.title)]) }] : []), ...(home.data || [])]);
  const titles = createMemo(() => destination() === 'list' ? library()?.favorites || [] : destination() === 'continue' ? distinctTitles([continuing().map(v => v.title)]) : distinctTitles(remote.data?.pages || []));
  const [highlighted, setHighlighted] = createSignal<Title>();
  let dwell: ReturnType<typeof setTimeout> | undefined;
  function highlight(title: Title) { clearTimeout(dwell); dwell = setTimeout(() => setHighlighted(title), 250); }
  createEffect(on(filterKey, () => { clearTimeout(dwell); setHighlighted(undefined); }));
  onCleanup(() => clearTimeout(dwell));
  const hero = () => highlighted() || shelves()[0]?.titles[0];
  const heroDetail = createQuery(() => ({ queryKey: ['detail', hero() ? titleKey(hero()!) : '', app.profileId(), app.authRevision()],
    enabled: destination() === 'home' && !!hero(), queryFn: ({ signal }) => hero()!.source === 'HIANIME' ? app.anime.detail(hero()!, signal) : app.flixtor.detail(hero()!, signal), retry: false }));
  createEffect(() => {
    if (destination() === 'home') app.setStage(heroDetail.data?.title || hero());
    else app.setStage(undefined);
  });
  const [delayed, setDelayed] = createSignal(false);
  createEffect(() => {
    const fetching = destination() === 'home' ? home.isFetching : remote.isFetching;
    if (!fetching) setDelayed(false);
    const timer = setTimeout(() => setDelayed(fetching), 120); onCleanup(() => clearTimeout(timer));
  });
  function open(title: Title, focus: string) {
    app.setSelected(title); app.titleCache.set(titleKey(title), title);
    if (app.titleCache.size > 256) app.titleCache.delete(app.titleCache.keys().next().value!);
    app.lastFocus = focus; navigate(`/detail?title=${encodeURIComponent(titleKey(title))}`);
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
      const focusId = (rail: number, title: Title) => `${key}:${rail}:${titleKey(title)}`;
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
            <text skipFocus width={950} height={150} fontSize={76} fontFamily="Roboto700" maxLines={2}>{hero()?.name || `Welcome, ${app.profile()?.name}`}</text>
            <text skipFocus width={920} height={38} fontSize={26} color={palette.muted} maxLines={1}>{[hero()?.year, hero()?.genres, hero()?.source === 'HIANIME' ? 'Anime' : ''].filter(Boolean).join('   ·   ')}</text>
            <text skipFocus width={850} height={105} fontSize={28} color={palette.muted} maxLines={3}>{heroDetail.data?.title.synopsis || hero()?.synopsis || 'Settle in. Find something worth watching.'}</text>
            <Row width={1200} height={72} gap={18}>
              <Show when={hero()}><Button id="hero-play" label={hero() && resumeViewing(continuing(), hero()!) ? 'Resume' : 'Play'} icon="play" primary autofocus={!origin} width={220} disabled={!heroDetail.data} action={() => app.run(async () => {
                const detail = heroDetail.data; if (!detail) return;
                const viewing = resumeViewing(continuing(), detail.title); navigate('/player'); await app.playback!.start(detail, resumeEpisode(detail, viewing), viewing?.version || undefined);
              })} /><Button id="hero-details" label="Details" width={220} action={() => open(hero()!, 'hero-details')} /></Show>
              <Show when={home.isError}><Button label="Try again" autofocus action={() => void home.refetch()} /></Show>
            </Row>
          </Column>
          <Show when={home.isPending}><Status message="Loading your catalogue…" /></Show>
          <Show when={home.isError}><Status message={failureMessages[failureOf(home.error)]} error /></Show>
          <For each={shelves()}>{(shelf, rail) => <Column width={1728} height={shelf.label === 'Continue Watching' ? 380 : 500} gap={24} scroll="none">
            <Row width={1728} height={48} gap={24} skipFocus={shelf.label !== 'Continue Watching'}><text skipFocus width={1250} height={48} fontSize={36} fontFamily="Roboto700">{shelf.label}</text>
              <Show when={shelf.label === 'Continue Watching'}><Button label="View Continue Watching" height={48} width={370} quiet action={() => navigate('/continue')} /></Show>
            </Row>
            <VirtualRow each={shelf.titles} width={1728} height={shelf.label === 'Continue Watching' ? 300 : 440} gap={28} displaySize={shelf.label === 'Continue Watching' ? 4 : 6} bufferSize={2} scroll="edge" wrap={false}
              selected={origin?.rail === rail() ? origin.item : 0}>
              {(title, index) => <Poster title={title()} width={shelf.label === 'Continue Watching' ? 390 : 240} landscape={shelf.label === 'Continue Watching'}
                viewing={shelf.label === 'Continue Watching' ? resumeViewing(continuing(), title()) : undefined} id={focusId(rail(), title())}
                autofocus={origin?.focus === focusId(rail(), title())} action={() => open(title(), focusId(rail(), title()))}
                onFocus={() => { const item = shelf.titles.findIndex(t => titleKey(t) === titleKey(title())); remember(rail(), item, focusId(rail(), title())); highlight(title()); }} />}
            </VirtualRow>
          </Column>}</For>
        </Column>
      </Show>;
    }}</Show>
    <Show when={sortMenu()}><Overlay title="Sort titles" close={() => setSortMenu(false)} width={700} height={510}><Menu width={620} height={438} title="Sort titles" options={[
      ['latest', 'Latest'], ['best', 'Top rated'], ['name', 'A–Z'],
    ].map(([value, label]) => ({ id: value, label, selected: sort() === value, action: () => { setSortMenu(false); setParams({ sort: value, page: undefined, window: undefined }); } }))} /></Overlay></Show>
  </>;
}

function TitleGrid(props: { titles: Title[]; prefix: string; columns: number; width: number; height: number; initial?: number; autofocus?: boolean; viewing?: Viewing[]; focus?(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const cardWidth = () => (props.width - (props.columns - 1) * 28 - 16) / props.columns;
  const rows = createMemo(() => chunk(props.titles, props.columns));
  return <view width={props.width} height={props.height} clipping>
    <VirtualColumn each={rows()} width={props.width} height={props.height} displaySize={2} bufferSize={1} gap={32} scroll="edge" selected={Math.floor((props.initial || 0) / props.columns)} wrap={false}
      onEndReachedThreshold={2} onEndReached={props.end}>
      {(row, rowIndex) => <Row x={8} y={10} width={props.width - 16} height={cardWidth() * 1.5 + 78} gap={28} scroll="none" selected={(props.initial || 0) % props.columns}>
        <For each={row()}>{title => <Poster title={title} width={cardWidth()} id={`${props.prefix}:0:${titleKey(title)}`} viewing={props.viewing && resumeViewing(props.viewing, title)}
          autofocus={props.autofocus !== false && (props.initial || 0) === props.titles.findIndex(t => titleKey(t) === titleKey(title))} action={() => props.open(title)}
          onFocus={() => { const index = props.titles.findIndex(t => titleKey(t) === titleKey(title)); props.focus?.(title, index); if (index >= props.titles.length - props.columns * 2) props.end(); }} />}</For>
      </Row>}
    </VirtualColumn>
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
