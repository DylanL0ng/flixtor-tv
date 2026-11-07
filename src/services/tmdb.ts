import { ServiceError, series, type Title } from '../domain/models';
import type { HttpResponse, Platform } from '../platform/platform';
import type { DiscoveryTitle } from './cinemeta';

export const METADATA_TTL = 6 * 60 * 60_000;
export type EntityKind = 'actor' | 'director' | 'studio';
export type MovieSort = 'popular' | 'release' | 'rating';
export interface Movie extends DiscoveryTitle { tmdbId: number; imdb?: string; popularity?: number; released?: string }
export interface Entity { id: number; name: string; image?: string; character?: string }
export interface MovieMetadata { title: Movie; cast: Entity[]; directors: Entity[]; studios: Entity[]; related: Movie[] }
export interface MoviePage { titles: Movie[]; page: number; next?: number }
const image = (value: unknown, size = 'w500') => typeof value === 'string' && /^\/[\w./-]+$/.test(value) ? `https://image.tmdb.org/t/p/${size}${value}` : undefined;
const id = (value: unknown) => Number.isSafeInteger(value) && Number(value) > 0;
const names = (value: unknown) => Array.isArray(value) ? value.filter(x => x && typeof x.name === 'string').map(x => x.name as string) : [];
export function movieOf(value: unknown): Movie | undefined {
  if (!value || typeof value !== 'object') return;
  const m = value as Record<string, any>;
  if (!id(m.id) || typeof m.title !== 'string' || !m.title.trim()) return;
  return { id: `tmdb:${m.id}`, tmdbId: m.id, popularity: Number.isFinite(m.popularity) ? m.popularity : 0, released: typeof m.release_date === 'string' ? m.release_date : undefined, imdb: /^tt\d{7,10}$/.test(m.imdb_id) ? m.imdb_id : undefined, type: 'movie', name: m.title.trim(),
    poster: image(m.poster_path), backdrop: image(m.backdrop_path, 'w1280'), year: /^\d{4}/.exec(m.release_date || '')?.[0],
    genres: names(m.genres), synopsis: typeof m.overview === 'string' ? m.overview : undefined,
    rating: Number.isFinite(m.vote_average) && m.vote_count > 0 ? Number(m.vote_average).toFixed(1) : undefined };
}
function entities(value: unknown, portrait = true): Entity[] {
  if (!Array.isArray(value)) return [];
  return [...new Map(value.flatMap(m => id(m?.id) && typeof m.name === 'string' ? [[m.id, { id: m.id, name: m.name, image: image(portrait ? m.profile_path : m.logo_path, 'w185'), character: typeof m.character === 'string' ? m.character : undefined }] as const] : [])).values()];
}
export function parseMovieMetadata(data: Record<string, any>): MovieMetadata {
  const title = movieOf(data); if (!title) throw new ServiceError('CHANGED');
  return { title, cast: entities(data.credits?.cast), directors: entities(data.credits?.crew?.filter((p: any) => p.job === 'Director')),
    studios: entities(data.production_companies, false), related: (data.recommendations?.results || []).flatMap((m: unknown) => movieOf(m) || []) };
}
const normalized = (s: string) => s.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
export function matchMovie(movie: Movie, candidates: Title[]) {
  const exact = candidates.filter(t => !series(t) && !!movie.imdb && t.imdb === movie.imdb);
  if (exact.length) return exact.length === 1 ? exact[0] : undefined;
  const matches = candidates.filter(t => !series(t) && (!t.imdb || !movie.imdb || t.imdb === movie.imdb) && !!movie.year && t.year === movie.year && normalized(t.name) === normalized(movie.name));
  return matches.length === 1 ? matches[0] : undefined;
}
export function directedMovies(credits: Record<string, any>) {
  return (Array.isArray(credits.crew) ? credits.crew : []).filter((c: any) => c.job === 'Director').flatMap((c: unknown) => movieOf(c) || []);
}
export function sortMovies(titles: Movie[], sort: MovieSort) {
  const unique = [...new Map(titles.map(t => [t.tmdbId, t])).values()];
  return unique.sort((a, b) => (sort === 'popular' ? (b.popularity || 0) - (a.popularity || 0) : sort === 'release' ? (b.released || '').localeCompare(a.released || '') : Number(b.rating || 0) - Number(a.rating || 0)) || a.name.localeCompare(b.name));
}
export class TmdbService {
  constructor(private platform: Platform) {}
  private async get(path: string, params: Record<string, string | number> = {}, signal?: AbortSignal): Promise<Record<string, any>> {
    const url = new URL(`/3${path}`, 'https://api.themoviedb.org');
    Object.entries({ language: 'en-US', ...params }).forEach(([k, v]) => url.searchParams.set(k, String(v)));
    const response = await this.platform.call<HttpResponse>('http', { client: 'tmdb', url: url.href }, signal);
    if (response.status < 200 || response.status >= 300) throw new ServiceError('UNAVAILABLE');
    try { const data = JSON.parse(response.body); if (!data || typeof data !== 'object') throw new Error(); return data; } catch { throw new ServiceError('CHANGED'); }
  }
  async detail(movieId: number, signal?: AbortSignal) {
    if (!id(movieId)) throw new ServiceError('CHANGED');
    return parseMovieMetadata(await this.get(`/movie/${movieId}`, { append_to_response: 'credits,recommendations' }, signal));
  }
  async identify(title: Pick<Title, 'name' | 'year' | 'imdb'>, signal?: AbortSignal) {
    const data = title.imdb ? await this.get(`/find/${title.imdb}`, { external_source: 'imdb_id' }, signal) : await this.get('/search/movie', { query: title.name, ...(title.year ? { year: title.year } : {}) }, signal);
    const matches = (data.movie_results || data.results || []).flatMap((m: unknown) => movieOf(m) || []).filter((m: Movie) => title.imdb || !!title.year && m.year === title.year && normalized(m.name) === normalized(title.name));
    return matches.length === 1 ? matches[0] as Movie : null;
  }
  async search(kind: 'people' | 'studios', query: string, page: number, signal?: AbortSignal) {
    const data = await this.get(`/search/${kind === 'people' ? 'person' : 'company'}`, { query: query.slice(0, 64), page }, signal);
    return { entities: entities(data.results, kind === 'people'), next: page < Math.min(data.total_pages || 0, 500) ? page + 1 : undefined };
  }
  async movies(options: { shelf?: 'trending' | 'rated' | 'genre' | 'similar'; genre?: number; movie?: number; kind?: EntityKind; entity?: number; sort?: MovieSort; page: number }, signal?: AbortSignal): Promise<MoviePage> {
    const { page, sort = 'popular', kind, entity, shelf } = options;
    if (!Number.isInteger(page) || page < 1 || page > 500) throw new ServiceError('CHANGED');
    if (kind && !id(entity)) throw new ServiceError('CHANGED');
    if (kind === 'director' || kind === 'actor') {
      const data = await this.get(`/person/${entity}/movie_credits`, {}, signal);
      const all = kind === 'director' ? directedMovies(data) : (data.cast || []).flatMap((m: unknown) => movieOf(m) || []);
      const ordered = sortMovies(all, sort), start = (page - 1) * 20;
      return { titles: ordered.slice(start, start + 20), page, next: start + 20 < ordered.length ? page + 1 : undefined };
    }
    const path = shelf === 'similar' ? `/movie/${options.movie}/recommendations` : shelf === 'trending' ? '/trending/movie/week' : '/discover/movie';
    const data = await this.get(path, { page, include_adult: 'false',
      sort_by: sort === 'release' ? 'primary_release_date.desc' : sort === 'rating' || shelf === 'rated' ? 'vote_average.desc' : 'popularity.desc',
      ...(sort === 'rating' || shelf === 'rated' ? { 'vote_count.gte': 200 } : {}),
      ...(options.genre ? { with_genres: options.genre } : {}), ...(kind === 'studio' ? { with_companies: entity! } : {}) }, signal);
    return { titles: (data.results || []).flatMap((m: unknown) => movieOf(m) || []), page, next: page < Math.min(data.total_pages || 0, 500) ? page + 1 : undefined };
  }
}
