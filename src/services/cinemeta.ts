import { ServiceError, series, type Title } from '../domain/models';
import type { HttpResponse, Platform } from '../platform/platform';

export const CINEMETA_BASE = 'https://cinemeta-catalogs.strem.io';
export const CINEMETA_TTL = 6 * 60 * 60_000;
export type CinemetaType = 'movie' | 'series';
export type CinemetaCatalog = 'top' | 'imdbRating';

export interface DiscoveryTitle {
  id: string;
  type: CinemetaType;
  name: string;
  poster?: string;
  backdrop?: string;
  year?: string;
  genres: string[];
  synopsis?: string;
  rating?: string;
}

function imageUrl(value: unknown, poster = false) {
  if (typeof value !== 'string' || !value) return undefined;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return undefined;
    if (poster && url.hostname === 'images.metahub.space') url.pathname = url.pathname.replace('/poster/small/', '/poster/medium/');
    return url.href;
  }
  catch { return undefined; }
}

export function parseCinemetaCatalog(raw: string, type: CinemetaType): DiscoveryTitle[] {
  const data = JSON.parse(raw) as { metas?: unknown };
  if (!Array.isArray(data.metas)) throw new ServiceError('UNAVAILABLE');
  const titles = data.metas.slice(0, 60).flatMap((item): DiscoveryTitle[] => {
    if (!item || typeof item !== 'object') return [];
    const meta = item as Record<string, unknown>;
    const id = typeof meta.id === 'string' ? meta.id : typeof meta.imdb_id === 'string' ? meta.imdb_id : '';
    if (meta.type !== type || !/^tt\d{7,10}$/.test(id) || typeof meta.name !== 'string' || !meta.name.trim()) return [];
    const release = typeof meta.releaseInfo === 'string' ? meta.releaseInfo : typeof meta.year === 'string' ? meta.year : '';
    const rating = typeof meta.imdbRating === 'string' || typeof meta.imdbRating === 'number' ? String(meta.imdbRating) : '';
    return [{
      id, type, name: meta.name.trim(), poster: imageUrl(meta.poster, true), backdrop: imageUrl(meta.background),
      year: /(?:^|\D)(\d{4})(?:\D|$)/.exec(release)?.[1],
      genres: Array.isArray(meta.genres) ? meta.genres.filter((genre): genre is string => typeof genre === 'string').slice(0, 8) : [],
      synopsis: typeof meta.description === 'string' ? meta.description : undefined,
      rating: rating && Number.isFinite(Number(rating)) ? rating : undefined,
    }];
  });
  return [...new Map(titles.map(title => [title.id, title])).values()];
}

export function matchPlayableTitle(discovery: DiscoveryTitle, candidates: Title[]): Title | undefined {
  const normalize = (name: string) => name.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return candidates.find(candidate => {
    if (series(candidate) !== (discovery.type === 'series') || normalize(candidate.name) !== normalize(discovery.name)) return false;
    return !candidate.year || !discovery.year || candidate.year === discovery.year;
  });
}

export class CinemetaService {
  private cache = new Map<string, { expires: number; titles: DiscoveryTitle[] }>();
  constructor(private platform: Platform) {}

  async catalog(type: CinemetaType, catalog: CinemetaCatalog = 'top', genre?: string, signal?: AbortSignal) {
    const key = `${type}:${catalog}:${genre || ''}`, cached = this.cache.get(key);
    if (cached && cached.expires > Date.now()) return cached.titles;
    const url = new URL(`/catalog/${type}/${catalog}.json`, CINEMETA_BASE);
    url.pathname = `/${catalog}/catalog/${type}/${catalog}.json${genre ? `/genre=${encodeURIComponent(genre)}` : ''}`;
    const response = await this.platform.call<HttpResponse>('http', { client: 'public', url: url.href }, signal);
    if (response.status < 200 || response.status >= 300) throw new ServiceError('UNAVAILABLE');
    try {
      const titles = parseCinemetaCatalog(response.body, type);
      this.cache.set(key, { titles, expires: Date.now() + CINEMETA_TTL });
      if (this.cache.size > 12) this.cache.delete(this.cache.keys().next().value!);
      return titles;
    }
    catch (error) { if (error instanceof ServiceError) throw error; throw new ServiceError('UNAVAILABLE'); }
  }
}
