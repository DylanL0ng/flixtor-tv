import { describe, expect, it, vi } from 'vitest';
import { CINEMETA_TTL, CinemetaService, matchPlayableTitle, parseCinemetaCatalog } from '../src/services/cinemeta';
import type { Title } from '../src/domain/models';
import type { Platform } from '../src/platform/platform';

const record = (overrides: Partial<Record<string, unknown>> = {}) => ({
  id: 'tt1234567', type: 'movie', name: 'Northbound', releaseInfo: '2026',
  poster: 'https://image.tmdb.org/t/p/w500/poster.jpg',
  background: 'https://image.tmdb.org/t/p/original/backdrop.jpg',
  genres: ['Adventure', 'Drama'], description: 'A story.', imdbRating: 7.8, ...overrides,
});

describe('Cinemeta discovery', () => {
  it('keeps IMDb discovery identity separate and maps full artwork metadata', () => {
    const [title] = parseCinemetaCatalog(JSON.stringify({ metas: [record()] }), 'movie');
    expect(title).toMatchObject({ id: 'tt1234567', type: 'movie', year: '2026', backdrop: 'https://image.tmdb.org/t/p/original/backdrop.jpg', genres: ['Adventure', 'Drama'], rating: '7.8' });
  });

  it('drops malformed, wrong-type, and unsafe artwork records', () => {
    const titles = parseCinemetaCatalog(JSON.stringify({ metas: [record({ id: '2' }), record({ type: 'series' }), record({ poster: 'http://example.com/poster.jpg', background: 'javascript:alert(1)' })] }), 'movie');
    expect(titles).toHaveLength(1);
    expect(titles[0]).toMatchObject({ poster: undefined, backdrop: undefined });
  });

  it('matches Flixtor result by title, type, and year when available', () => {
    const [discovery] = parseCinemetaCatalog(JSON.stringify({ metas: [record()] }), 'movie');
    const candidates: Title[] = [
      { id: '1', path: '/watch/tv/1/northbound', name: 'Northbound', year: '2026' },
      { id: '2', path: '/watch/movie/2/northbound', name: 'Northbound', year: '2025' },
      { id: '3', path: '/watch/movie/3/northbound', name: 'Northbound', year: '2026' },
    ];
    expect(matchPlayableTitle(discovery, candidates)?.id).toBe('3');
    expect(matchPlayableTitle({ ...discovery, name: 'Not Listed' }, candidates)).toBeUndefined();
  });

  it('matches Unicode titles after normalization without mixing media types', () => {
    const [discovery] = parseCinemetaCatalog(JSON.stringify({ metas: [record({ name: 'Été 東京' })] }), 'movie');
    const candidate: Title = { id: '4', path: '/watch/movie/4/ete', name: 'Ete 東京', year: '2026' };
    expect(matchPlayableTitle(discovery, [candidate])?.id).toBe('4');
    expect(matchPlayableTitle({ ...discovery, type: 'series' }, [candidate])).toBeUndefined();
  });

  it('caches catalog responses for six hours', async () => {
    vi.useFakeTimers();
    try {
      const call = vi.fn(async () => ({ status: 200, body: JSON.stringify({ metas: [record()] }) }));
      const service = new CinemetaService({ call } as unknown as Platform);
      await service.catalog('movie'); await service.catalog('movie');
      expect(call).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(CINEMETA_TTL + 1);
      await service.catalog('movie');
      expect(call).toHaveBeenCalledTimes(2);
    } finally { vi.useRealTimers(); }
  });
});
