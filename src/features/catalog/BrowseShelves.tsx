import { createMemo } from 'solid-js';
import { createInfiniteQuery } from '@tanstack/solid-query';
import { useApp } from '../../app/context';
import { failureOf, type Title, type Viewing } from '../../domain/models';
import type { AnimeCategory } from '../../services/anime';
import { browseCategories, distinctTitles, nextCataloguePage, shelfQueryKey } from './catalog-state';
import { CatalogShelves, type BrowseOrigin, type CatalogShelf, type ShelfTitle } from './CatalogShelves';

/** Mounted by the canonical route/filter key; query observers stay independent per category. */
export function BrowseShelves(props: {
  destination: string; sort: string; query: string; prefix: string; origin?: BrowseOrigin; viewing: Viewing[];
  focus(shelf: string, title: ShelfTitle, index: number, rail: number, id: string): void;
  open(title: Title, id: string): void;
}) {
  const app = useApp();
  const shelves: CatalogShelf[] = browseCategories(props.destination, props.sort, props.query).map(category => {
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
      ...category,
      get titles() { return titles(); }, get pending() { return query.isPending; },
      get fetching() { return query.isFetching; }, get error() { return query.error; },
      retry() { void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch()); },
      end() { if (query.hasNextPage && !query.isFetching && !query.isFetchNextPageError) void query.fetchNextPage(); },
    };
  });
  return <CatalogShelves shelves={shelves} height={742} prefix={props.prefix} origin={props.origin} viewing={props.viewing}
    autofocus focus={props.focus} open={(title, id) => props.open(title as Title, id)} />;
}
