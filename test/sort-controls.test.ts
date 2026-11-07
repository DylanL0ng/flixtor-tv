import { expect, it } from 'vitest';
import { FlixtorService, parseCards, parseSortControls } from '../src/services/flixtor';
import { catalogFixture } from './catalog-fixture';
import fixtures from './fixtures/http.json';
import type { Platform } from '../src/platform/platform';
it('learns provider sorting values from labelled controls', () => {
  expect(parseSortControls('<a data-s="rating_desc">Top rated</a><button data-s="title_asc">A–Z</button><a data-s="added">Recently added</a>')).toEqual({ best: 'rating_desc', name: 'title_asc', latest: 'added' });
});
it('uses learnt values across pages and preserves server ordering', async () => {
  const requests: string[] = [];
  const platform = { call: async (_method: string, args: { url: string }) => {
    requests.push(args.url);
    return { status: 200, body: args.url.endsWith('/home') ? '<meta name="csrf-token" content="test">' : '<a data-s="rating_desc">Top rated</a><a data-s="title_asc">A–Z</a>' + fixtures.catalog };
  } } as unknown as Platform;
  const service = new FlixtorService(platform);
  await service.browse('movies', 1, 'name'); await service.browse('movies', 2, 'best');
  expect(requests.some(r => r.endsWith('page=1&sort=title_asc'))).toBe(true);
  expect(requests.some(r => r.endsWith('page=2&sort=rating_desc'))).toBe(true);
});
it('default fixture has genuine A–Z ordering and terminal pagination', () => {
  const latest = parseCards(catalogFixture(fixtures.catalog, 'latest', '1'));
  const names = parseCards(catalogFixture(fixtures.catalog, 'name', '1')).map(t => t.name);
  expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'en')));
  expect(names).not.toEqual(latest.map(t => t.name));
  expect(parseCards(catalogFixture(fixtures.catalog, 'name', '2'))).toEqual([]);
});
