import { createEffect, createMemo, createSignal, For, Show } from 'solid-js';
import { createInfiniteQuery } from '@tanstack/solid-query';
import { useNavigate, useSearchParams } from '@solidjs/router';
import { Column, Row, VirtualGrid, VirtualRow } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Poster, Status, palette } from '../../ui/components';
import { restoreFocus } from '../../ui/focus';
import { ContentError } from '../../ui/ContentError';
import { METADATA_TTL, type Entity, type EntityKind, type Movie, type MovieMetadata, type MovieSort } from '../../services/tmdb';
import { actorSearchQuery } from '../../services/flixtor';
import { failureOf, titleKey, type Title } from '../../domain/models';
import { distinctTitles, nextCataloguePage } from './catalog-state';
import { TitleGrid } from './TitleGrid';
import { narratePage } from '../../ui/narration';

export const movieRoute = (movie: { tmdbId?: number; id: string; name: string; year?: string }) => movie.tmdbId ? `/detail?tmdb=${movie.tmdbId}` : `/detail?imdb=${encodeURIComponent(movie.id)}&name=${encodeURIComponent(movie.name)}&year=${movie.year || ''}`;
export const entityRoute = (kind: EntityKind, entity: Entity) => `/discover?kind=${kind}&id=${entity.id}&name=${encodeURIComponent(entity.name)}`;

export function MovieGrid(props: { titles: Movie[]; prefix: string; width?: number; height?: number; columns?: number; autofocus?: boolean; end(): void }) {
  const app = useApp(), navigate = useNavigate();
  const columns = () => props.columns || 6, width = () => props.width || 1728, height = () => props.height || 700;
  const saved = app.browseOrigins.get(props.prefix);
  const initial = () => Math.max(0, saved?.title ? props.titles.findIndex(m => m.id === saved.title) : saved?.item || 0);
  return <view width={width()} height={height()} clipping={[20, 20, 20, 20]} forwardFocus={0}>
    <VirtualGrid each={props.titles} x={16} y={12} width={width() - 32} height={height() - 24} columns={columns()} rows={1} buffer={1} gap={28} selected={initial()} scroll="always" wrap={false}
      onEndReached={props.end} onEndReachedThreshold={columns() * 2}>
      {movie => <Poster title={movie()} width={(width() - 32 - (columns() - 1) * 28) / columns()} id={`${props.prefix}:${movie().id}`}
        autofocus={props.autofocus !== false && props.titles[initial()]?.id === movie().id}
        onFocus={() => { const item = props.titles.findIndex(m => m.id === movie().id); app.browseOrigins.set(props.prefix, { rail: 0, item, focus: `${props.prefix}:${movie().id}`, title: movie().id }); if (app.browseOrigins.size > 32) app.browseOrigins.delete(app.browseOrigins.keys().next().value!); if (item >= props.titles.length - columns() * 2) props.end(); }}
        action={() => navigate(movieRoute(movie()))} />}
    </VirtualGrid>
  </view>;
}

function CreditRail(props: { label: string; kind: EntityKind; items: Entity[] }) {
  const navigate = useNavigate();
  return <Column width={1728} height={props.kind === 'actor' ? 480 : 160} gap={24} scroll="none">
    <text skipFocus width={1600} height={50} fontSize={36} fontFamily="Roboto700">{props.label}</text>
    <view width={1728} height={props.kind === 'actor' ? 404 : 86} clipping={[20, 20, 20, 20]} forwardFocus={0}>
      <VirtualRow each={props.items} x={24} width={1680} height={props.kind === 'actor' ? 404 : 86} gap={28} displaySize={props.kind === 'actor' ? 7 : 4} bufferSize={2} scroll="edge" wrap={false} animationSettings={{ duration: 100 }}>
        {person => <Show when={props.kind === 'actor'} fallback={<Button item={person()} label={person().name} width={380} action={() => navigate(entityRoute(props.kind, person()))} />}>
          <Poster item={person()} title={{ name: person().name, initials: person().name.split(/\s+/).filter(Boolean).slice(0, 2).map(n => n[0]).join(''), poster: person().image, year: person().character }} width={210} id={`credit-${props.kind}-${person().id}`} action={() => navigate(entityRoute(props.kind, person()))} />
        </Show>}
      </VirtualRow>
    </view>
  </Column>;
}
export function MetadataSections(props: { metadata: MovieMetadata }) {
  const navigate = useNavigate();
  return <>
    <Show when={props.metadata.directors.length}><CreditRail label="Directed by" kind="director" items={props.metadata.directors} /></Show>
    <Show when={props.metadata.cast.length}><CreditRail label="Cast · movies and TV shows" kind="actor" items={props.metadata.cast} /></Show>
    <Show when={props.metadata.studios.length}><CreditRail label="Production studios" kind="studio" items={props.metadata.studios} /></Show>
    <Show when={props.metadata.related.length}><Column width={1728} height={600} gap={24} scroll="none">
      <text skipFocus width={1400} height={50} fontSize={36} fontFamily="Roboto700">More like this</text>
      <view width={1728} height={510} clipping={[20, 20, 20, 20]} forwardFocus={0}><VirtualRow each={props.metadata.related} x={24} width={1680} height={510} gap={28} displaySize={6} scroll="edge" wrap={false} animationSettings={{ duration: 100 }}>
        {movie => <Poster title={movie()} width={250} id={`related-${movie().tmdbId}`} action={() => navigate(movieRoute(movie()))} />}
      </VirtualRow></view>
    </Column></Show>
  </>;
}

export function EntityResults(props: { kind: 'people' | 'studios'; query: string; prefix: string; width: number }) {
  const app = useApp(), navigate = useNavigate();
  const origin = app.browseOrigins.get(props.prefix);
  const focusId = (entity: Entity, directing = false) => `${props.prefix}:${entity.id}:${directing ? 'director' : props.kind}`;
  const remember = (entity: Entity, directing = false) => {
    app.browseOrigins.set(props.prefix, { rail: 0, item: items().findIndex(e => e.id === entity.id), title: String(entity.id), focus: focusId(entity, directing) });
    if (app.browseOrigins.size > 32) app.browseOrigins.delete(app.browseOrigins.keys().next().value!);
  };
  const query = createInfiniteQuery(() => ({ queryKey: ['tmdb', 'search', props.kind, props.query], enabled: !!props.query.trim() && !!app.host.tmdb,
    initialPageParam: 1, maxPages: 8, staleTime: METADATA_TTL,
    queryFn: ({ pageParam, signal }) => app.tmdb.search(props.kind, props.query, pageParam, signal), getNextPageParam: last => last.next }));
  const items = createMemo(() => [...new Map((query.isSuccess ? query.data : undefined)?.pages.flatMap(p => p.entities).map(e => [e.id, e]) || []).values()]);
  const end = () => { if (query.hasNextPage && !query.isFetching) void query.fetchNextPage(); };
  const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map(n => n[0]).join('').toUpperCase();
  const rowGap = 24;
  // Pick columns that fill the column width (cards stay readable, max 6) and then the
  // rows that fill the viewport height (2-4). This uses the full width with no leftover
  // gap and keeps the multi-row grid complete instead of a sparse, short row block.
  const columns = () => {
    let best = 4;
    let bestArea = 0;
    for (let c = 6; c >= 2; c--) {
      const cardW = (props.width - 32 - (c - 1) * rowGap) / c;
      if (cardW < 140) continue;
      const cardH = cardW * 0.5625 + 80;
      const r = Math.max(2, Math.min(4, Math.floor((600 - 12) / (cardH + rowGap))));
      const area = c * r;
      if (area > bestArea) { bestArea = area; best = c; }
    }
    return best;
  };
  const cardWidth = () => (props.width - 32 - (columns() - 1) * rowGap) / columns();
  const cardHeight = () => cardWidth() * 0.5625 + 80;
  const visibleRows = () => Math.min(4, Math.max(2, Math.floor((600 - 12) / (cardHeight() + rowGap))));
  const viewportHeight = () => visibleRows() * cardHeight() + (visibleRows() - 1) * rowGap + 20;
  return <>
    <Show when={!app.host.tmdb}><Status width={props.width} message="People and studio discovery needs TMDB configuration. Title search remains available." /></Show>
    <Show when={query.isPending && query.isFetching}><Status width={props.width} message="Searching…" /></Show>
    <Show when={query.isSuccess && !items().length}><Status width={props.width} message="No matches. Try another name." /></Show>
    <ContentError screenKey={props.prefix} operations={[{ id: 'entities', error: query.error, fetching: query.isFetching, retry: () => query.refetch() }]} />
    <Show when={items().length}><view width={props.width} height={viewportHeight()} clipping={[20, 20, 20, 20]} forwardFocus={0}>
      <VirtualGrid each={items()} x={16} y={12} width={props.width - 32} height={viewportHeight() - 24} gap={rowGap} columns={columns()} rows={visibleRows()} buffer={1} scroll="always" wrap={false} onEndReached={end} onEndReachedThreshold={columns() * 2} selected={origin?.item || 0}>
        {entity => <Poster item={entity()} title={{ name: entity().name, initials: initials(entity().name), poster: entity().image }} width={cardWidth()} landscape id={focusId(entity())} autofocus={origin?.focus === focusId(entity())} onFocus={() => remember(entity())} action={() => navigate(entityRoute(props.kind === 'people' ? 'actor' : 'studio', entity()))} />}
      </VirtualGrid>
    </view></Show>
  </>;
}

export function DiscoveryPage() {
  const [params] = useSearchParams();
  return <Show when={params.kind === 'actor'} fallback={<MetadataDiscoveryPage />}><ActorTitles name={String(params.name || '').trim()} /></Show>;
}

function ActorTitles(props: { name: string }) {
  const app = useApp(), navigate = useNavigate();
  narratePage(() => `Starring ${props.name}`, () => 'Movies and TV shows on FlixTor');
  const key = () => ['catalog', 'actor', props.name, 'movies,tvshows', app.profileId(), app.authRevision()] as const;
  const prefix = () => JSON.stringify(key());
  const query = createInfiniteQuery(() => ({ queryKey: key(), enabled: !!props.name,
    initialPageParam: 1, maxPages: 8,
    queryFn: ({ pageParam, signal }) => app.flixtor.search(actorSearchQuery(props.name), pageParam, signal, 'movies,tvshows'),
    getNextPageParam: nextCataloguePage,
    retry: (count, error) => failureOf(error) === 'NETWORK' && count < 1,
  }));
  // Avoid suspending the mounted canvas while a new actor's query is pending.
  const titles = createMemo(() => distinctTitles((query.isSuccess ? query.data?.pages : undefined) || []));
  const [highlighted, setHighlighted] = createSignal<Title>();
  createEffect(() => { prefix(); setHighlighted(undefined); });
  createEffect(() => { app.setStage(highlighted() || titles()[0]); });
  const end = () => { if (query.hasNextPage && !query.isFetching && !query.isFetchNextPageError) void query.fetchNextPage(); };
  const open = (title: Title) => {
    app.setSelected(title); app.titleCache.set(titleKey(title), title);
    if (app.titleCache.size > 256) app.titleCache.delete(app.titleCache.keys().next().value!);
    app.lastFocus = `${prefix()}:0:${titleKey(title)}`;
    navigate(`/detail?title=${encodeURIComponent(titleKey(title))}`);
  };
  return <Column width={1728} height={848} gap={24} scroll="none">
    <Row width={1728} height={72} gap={20}><Button id="actor-back" label="Back" icon="back" width={160} autofocus={!app.browseOrigins.has(prefix())} action={() => navigate(-1)} /><text skipFocus width={1450} height={68} fontSize={46} maxLines={1} fontFamily="Roboto700">{`Starring ${props.name}`}</text></Row>
    <text skipFocus width={1728} height={40} fontSize={24} color={palette.muted}>{query.isFetching ? query.isPending ? 'Searching FlixTor movies and TV shows…' : 'Loading more…' : 'Movies and TV shows on FlixTor'}</text>
    <Show when={query.isSuccess && !titles().length}><Status message="No movies or TV shows found for this actor." /></Show>
    <ContentError screenKey={prefix()} operations={[{ id: 'actor', error: query.error, fetching: query.isFetching,
      retry: () => query.isFetchNextPageError ? query.fetchNextPage() : query.refetch() }]} />
    <Show when={prefix()} keyed>{id => {
      const origin = app.browseOrigins.get(id);
      return <Show when={titles().length}><TitleGrid titles={titles()} prefix={id} columns={6} width={1728} height={660} initial={origin?.item || 0}
        up={() => restoreFocus('actor-back')} end={end} open={open} focus={(title, item) => {
          setHighlighted(title);
          app.browseOrigins.set(id, { rail: 0, item, title: titleKey(title), focus: `${id}:0:${titleKey(title)}` });
          if (app.browseOrigins.size > 32) app.browseOrigins.delete(app.browseOrigins.keys().next().value!);
        }} /></Show>;
    }}</Show>
  </Column>;
}

function MetadataDiscoveryPage() {
  const app = useApp(), navigate = useNavigate(), [params, setParams] = useSearchParams();
  const kind = (): EntityKind | undefined => ['actor', 'director', 'studio'].includes(String(params.kind)) ? params.kind as EntityKind : undefined;
  const sort = (): MovieSort => ['release', 'rating'].includes(String(params.sort)) ? params.sort as MovieSort : 'popular';
  const key = () => JSON.stringify(['tmdb', 'movies', kind(), Number(params.id), Number(params.genre), sort(), params.shelf]);
  const query = createInfiniteQuery(() => ({ queryKey: JSON.parse(key()), enabled: !!app.host.tmdb && kind() !== 'actor',
    initialPageParam: 1, maxPages: 8, staleTime: METADATA_TTL,
    queryFn: ({ pageParam, signal }) => app.tmdb.movies({ kind: kind(), entity: Number(params.id), genre: Number(params.genre) || undefined, sort: sort(), shelf: params.shelf === 'rated' ? 'rated' : 'genre', page: pageParam }, signal),
    getNextPageParam: last => last.next }));
  const titles = createMemo(() => [...new Map((query.isSuccess ? query.data : undefined)?.pages.flatMap(p => p.titles).map(m => [m.tmdbId, m]) || []).values()]);
  const label = () => kind() === 'director' ? `Directed by ${params.name || ''}` : kind() === 'actor' ? `Starring ${params.name || ''}` : params.name || 'Discover movies';
  createEffect(() => { app.setStage(titles()[0]); });
  return <Column width={1728} height={848} gap={24} scroll="none">
    <Row width={1728} height={72} gap={20}><Button id="discovery-back" label="Back" icon="back" width={160} action={() => navigate(-1)} /><text skipFocus width={1450} height={68} fontSize={46} maxLines={1} fontFamily="Roboto700">{label()}</text></Row>
    <Row width={1728} height={68} gap={20} onUp={() => restoreFocus('discovery-back')}>
      <For each={[['popular', 'Popular'], ['release', 'Newest releases'], ['rating', 'Top rated']] as const}>{([value, label]) => <Button label={label} width={300} selected={sort() === value} action={() => setParams({ sort: value })} />}</For>
    </Row>
    <Show when={!app.host.tmdb}><Status message="Movie discovery needs TMDB configuration. Browse Movies or search by title instead." /></Show>
    <text skipFocus width={1728} height={40} fontSize={24} color={palette.muted}>{query.isFetching ? query.isPending ? 'Finding movies…' : 'Loading more…' : ''}</text>
    <Show when={query.isSuccess && !titles().length}><Status message="No movies found for this selection." /></Show>
    <ContentError screenKey={key()} operations={[{ id: 'discovery', error: query.error, fetching: query.isFetching, retry: () => query.refetch() }]} />
    <Show when={key()} keyed>{prefix => <Show when={titles().length}><MovieGrid titles={titles()} prefix={prefix} height={560} end={() => { if (query.hasNextPage && !query.isFetching) void query.fetchNextPage(); }} /></Show>}</Show>
  </Column>;
}
