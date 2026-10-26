import { expect, it, vi } from 'vitest';
import { browseCategories, distinctTitles, nextCataloguePage, shelfQueryKey } from '../src/features/catalog/catalog-state';
import { AnimeService, type AnimeCategory } from '../src/services/anime';
import type { Platform } from '../src/platform/platform';
import type { Title } from '../src/domain/models';
import fixtures from './fixtures/http.json';

it('orders category shelves from URL sort and isolates all server inputs in query keys', () => {
  expect(browseCategories('movies', 'name', '').map(s => s.id)).toEqual(['name', 'latest', 'best']);
  expect(browseCategories('shows', 'best', '').map(s => s.id)).toEqual(['best', 'latest', 'name']);
  expect(browseCategories('anime', '', '').map(s => s.id)).toEqual(['most-popular', 'top-airing', 'recently-updated']);
  expect(browseCategories('anime', '', ' Naruto ').map(s => s.id)).toEqual(['search']);
  const key = shelfQueryKey('movies', 'latest', '', 'a', 1);
  for (const other of [shelfQueryKey('shows', 'latest', '', 'a', 1), shelfQueryKey('movies', 'best', '', 'a', 1),
    shelfQueryKey('movies', 'latest', 'q', 'a', 1), shelfQueryKey('movies', 'latest', '', 'b', 1), shelfQueryKey('movies', 'latest', '', 'a', 2)]) expect(other).not.toEqual(key);
});

it('deduplicates overlapping pages and stops empty or repeated pagination', () => {
  const title = (id: string): Title => ({ id, source: 'FLIXTOR', type: 'MOVIE', path: `/watch/${id}`, name: id });
  const a = title('1'), b = title('2');
  expect(distinctTitles([[a], [a, b]])).toEqual([a, b]);
  expect(nextCataloguePage([a, b], [[a], [a, b]], 2)).toBe(3);
  expect(nextCataloguePage([a], [[a], [a]], 2)).toBeUndefined();
  expect(nextCataloguePage([], [[a], []], 2)).toBeUndefined();
});

it('maps typed Anime categories to first-party paginated requests and forwards cancellation', async () => {
  const call = vi.fn(async () => ({ status: 200, body: fixtures.anime }));
  const service = new AnimeService({ call } as unknown as Platform);
  const controller = new AbortController();
  for (const category of ['most-popular', 'top-airing', 'recently-updated'] as const) {
    expect((await service.category(category, 2, controller.signal)).length).toBeGreaterThan(0);
    expect(call).toHaveBeenLastCalledWith('http', expect.objectContaining({ client: 'anime', url: `https://hianime.at/${category}?page=2` }), controller.signal);
  }
  const count = call.mock.calls.length;
  for (const page of [0, -1, 1.5, 10001]) await expect(service.category('top-airing', page)).rejects.toThrow('CHANGED');
  await expect(service.category('../evil' as AnimeCategory, 1)).rejects.toThrow('CHANGED');
  expect(call).toHaveBeenCalledTimes(count);
  await service.browse('Naruto', 1, controller.signal);
  expect(call).toHaveBeenLastCalledWith('http', expect.objectContaining({ url: 'https://hianime.at/search?page=1&keyword=Naruto' }), controller.signal);
});
