import { expect, it, vi } from 'vitest';
import { directedMovies, matchMovie, movieOf, parseMovieMetadata, TmdbService } from '../src/services/tmdb';
import type { Platform } from '../src/platform/platform';
import { tmdbFixture } from './tmdb-fixtures';
const raw = { id: 42, title: 'The Movie', release_date: '2020-01-01', imdb_id: 'tt1234567', vote_average: 8, vote_count: 500 };
it('keeps metadata IDs separate and rejects malformed IDs and artwork', () => {
  expect(movieOf({ ...raw, poster_path: 'https://untrusted.example/a' })).toMatchObject({ id: 'tmdb:42', tmdbId: 42, imdb: 'tt1234567', poster: undefined });
  expect(movieOf({ ...raw, id: -1 })).toBeUndefined();
});
it('filters directing filmographies by actual job and deduplicates credits', () => {
  expect(directedMovies({ crew: [{ ...raw, job: 'Writer' }, { ...raw, job: 'Director' }] })).toHaveLength(1);
  const meta = parseMovieMetadata(tmdbFixture('/3/movie/2', new URLSearchParams()));
  expect(meta.directors.map(p => p.name)).toEqual(['Sam Director']);
  expect(meta.cast[0].character).toBe('The traveller'); expect(meta.studios[0].id).toBe(201);
});
it('never resolves ambiguous remakes or series to a playback title', () => {
  const movie = movieOf(raw)!;
  const candidate = { id: '1', name: 'The Movie', year: '2020', path: '/watch/movie/1/a' };
  expect(matchMovie(movie, [candidate])).toBe(candidate);
  expect(matchMovie(movie, [candidate, { ...candidate, id: '2' }])).toBeUndefined();
  expect(matchMovie(movie, [{ ...candidate, year: '2019' }])).toBeUndefined();
  expect(matchMovie(movie, [{ ...candidate, path: '/watch/tv/1/a' }])).toBeUndefined();
  expect(matchMovie(movie, [{ ...candidate, name: 'Alternative', imdb: raw.imdb_id }])).toBeTruthy();
});
it('uses dedicated scope, propagates AbortSignal, and paginates director results after filtering', async () => {
  const call = vi.fn(async () => ({ status: 200, body: JSON.stringify({ crew: [{ ...raw, job: 'Writer' }, { ...raw, job: 'Director' }] }) }));
  const service = new TmdbService({ call } as unknown as Platform), signal = new AbortController().signal;
  const results = await service.movies({ kind: 'director', entity: 9, page: 1 }, signal);
  expect(results.titles).toHaveLength(1); expect(results.next).toBeUndefined();
  expect(call).toHaveBeenCalledWith('http', expect.objectContaining({ client: 'tmdb', url: expect.stringContaining('/person/9/movie_credits') }), signal);
});
