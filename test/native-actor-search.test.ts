import { expect, it, vi } from 'vitest';
import { actorSearchQuery, FlixtorService } from '../src/services/flixtor';
import { series } from '../src/domain/models';
import type { Platform } from '../src/platform/platform';
import fixtures from './fixtures/http.json';

it('uses FlixTor actor syntax, both media tabs, pagination, and request cancellation signal', async () => {
  const call = vi.fn(async (_method: string, args: Record<string, unknown>, _signal?: AbortSignal) => ({ status: 200,
    body: String(args.url).endsWith('/home') ? '<meta name="csrf-token" content="fixture">' : fixtures.catalog }));
  const service = new FlixtorService({ call } as unknown as Platform), signal = new AbortController().signal;
  const results = await service.search(actorSearchQuery('  Tom Hardy  '), 2, signal, 'movies,tvshows');
  const request = call.mock.calls.find(([, args]) => String(args.url).includes('/search/'))!;
  const url = new URL(String(request[1].url));
  expect(url.pathname).toBe('/search/%40actor%3A%20Tom%20Hardy');
  expect(url.searchParams.get('tab')).toBe('movies,tvshows');
  expect(url.searchParams.get('page')).toBe('2'); expect(url.searchParams.get('ajax')).toBe('1');
  expect(request[2]).toBe(signal);
  expect(results.some(series)).toBe(true); expect(results.some(t => !series(t))).toBe(true);
});
