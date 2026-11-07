import { useNavigate } from '@solidjs/router';
import { restoreFocus } from '../../ui/focus';
import { Show } from 'solid-js';
import { TitleGrid } from './TitleGrid';
import { titleKey } from '../../domain/models';
import { createMemo, type JSX } from 'solid-js';
import { createInfiniteQuery } from '@tanstack/solid-query';
import { useApp } from '../../app/context';
import { failureOf, type Title, type Viewing } from '../../domain/models';
import type { AnimeCategory } from '../../services/anime';
import { browseCategories, distinctTitles, nextCataloguePage, shelfQueryKey } from './catalog-state';
import { CatalogShelves, type BrowseOrigin, type CatalogShelf, type ShelfTitle } from './CatalogShelves';

/** Mounted by the canonical route/filter key; query observers stay independent per category. */
export function BrowseShelves(props: {
  full?: string; destination: string; sort: string; query: string; prefix: string; origin?: BrowseOrigin; viewing: Viewing[]; header?: JSX.Element;
  focus(shelf: string, title: ShelfTitle, index: number, rail: number, id: string): void;
  open(title: Title, id: string): void;
}) {
  const app = useApp(), navigate = useNavigate();
  const shelves: CatalogShelf[] = browseCategories(props.destination, props.sort, props.query).filter(c => !props.full || c.id === props.full).map(category => {
    const query = createInfiniteQuery(() => ({
      queryKey: shelfQueryKey(props.destination, category.id, props.query, app.profileId(), app.authRevision()),
      initialPageParam: 1, maxPages: 8,
      queryFn: ({ pageParam, signal }) => props.destination === 'anime'
        ? category.id === 'search' ? app.anime.browse(props.query.trim(), pageParam, signal) : app.anime.category(category.id as AnimeCategory, pageParam, signal)
        : app.flixtor.browse(props.destination === 'shows' ? 'tvshows' : 'movies', pageParam, category.id, signal),
      getNextPageParam: nextCataloguePage,
      retry: (count, error) => failureOf(error) === 'NETWORK' && count < 1,
    }));
    const titles = createMemo(() => distinctTitles(query.data?.pages || []));
    return {
      ...category, viewAll: () => navigate(`/${props.destination}?shelf=${category.id}&sort=${category.id}`),
      get titles() { return titles(); }, get pending() { return query.isPending; },
      get fetching() { return query.isFetching; }, get error() { return query.error; },
      retry() { return query.isFetchNextPageError ? query.fetchNextPage() : query.refetch(); },
      end() { if (query.hasNextPage && !query.isFetching && !query.isFetchNextPageError) void query.fetchNextPage(); },
    };
  });
  return <Show when={!props.full} fallback={<TitleGrid interactiveHeader up={() => restoreFocus('catalog-sort')} header={props.header} titles={shelves[0]?.titles as Title[] || []} prefix={props.prefix} columns={6} width={1728} height={848} initial={props.origin?.item || 0} viewing={props.viewing} end={() => shelves[0]?.end?.()} open={title => props.open(title, `${props.prefix}:0:${titleKey(title)}`)} focus={(title, index) => props.focus(props.full!, title, index, 0, `${props.prefix}:0:${titleKey(title)}`)} />}><CatalogShelves shelves={shelves} height={848} header={props.header} prefix={props.prefix} origin={props.origin} viewing={props.viewing}
    autofocus focus={props.focus} open={(title, id) => props.open(title as Title, id)} /></Show>;
}
