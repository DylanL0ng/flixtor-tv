export type Source = 'FLIXTOR' | 'HIANIME';
export type AnimeVersion = 'DUB' | 'SUB';
export type Account = 'CHECKING' | 'GUEST' | 'SIGNED_IN' | 'VIP' | 'EXPIRED' | 'UNAVAILABLE';
export type Failure = 'NETWORK' | 'ACCESS' | 'VIP' | 'EXPIRED' | 'BLOCKED' | 'CHANGED' | 'MEDIA' | 'SIGNED_OUT' | 'UNAVAILABLE' | 'UNSUPPORTED' | 'CAPACITY';
export class ServiceError extends Error { constructor(public failure: Failure) { super(failure); } }
export interface Title {
  id: string; path: string; name: string; poster?: string; year?: string; rating?: string;
  genres?: string; synopsis?: string; backdrop?: string; imdb?: string; source?: Source; type?: 'MOVIE' | 'SERIES';
}
export interface StageArtwork { poster?: string; backdrop?: string }
export interface Episode { season: number; number: number; name: string; providerId?: string; displayNumber?: string; order?: number }
export interface Detail { title: Title; episodes: Episode[] }
export interface Shelf { label: string; titles: Title[] }
export interface Caption { uri: string; label: string; mime: string; default?: boolean }
export interface AnimeMapping { malId: number; episode: number }
export interface Stream { uri: string; captions: Caption[]; headers: Record<string, string>; mime?: string; animeMapping?: AnimeMapping }
export interface Viewing { title: Title; episode?: Episode | null; position: number; duration: number; version?: AnimeVersion | null }
export interface Profile { id: string; name: string; avatar: number; quality: number; autoplay: boolean; locked: boolean; largerBuffer: boolean }
export type SkipAction = 'ASK' | 'AUTO' | 'OFF';
export type SegmentType = 'intro' | 'recap' | 'outro' | 'post_credits' | 'mixed-op' | 'mixed-ed' | 'custom';
export interface Segment { id: string; type: SegmentType; typeId: string; start: number; end: number; action?: SkipAction; source?: 'LOCAL' | 'INTRODB' | 'ANISKIP' }
export interface Release { versionCode: number; versionName: string; minSdk: number; sha256: string; apkUrl: string }
export const series = (t: Title) => t.type ? t.type === 'SERIES' : t.path.startsWith('/watch/tv/');
export const titleKey = (t: Title) => `${t.source === 'HIANIME' ? 'hianime:' : ''}${series(t) ? 't' : 'm'}:${t.id}`;
export const episodeKey = (e?: Episode | null) => e ? e.providerId ? `anime:${e.providerId}` : `${e.season}:${e.number}` : '';
export const playbackKey = (t: Title, e?: Episode | null, v?: AnimeVersion | null) => `${titleKey(t)}${e ? ':' + episodeKey(e) : ''}${v ? ':' + v.toLowerCase() : ''}`;
export const orderedEpisodes = (episodes: Episode[]) => [...episodes].sort((a, b) => a.season - b.season || (a.order ?? a.number) - (b.order ?? b.number));
export const finished = (v: Viewing) => v.duration > 0 && v.position >= v.duration - 30_000;
export const fraction = (v: Viewing) => v.duration <= 0 ? 0 : finished(v) ? 1 : Math.max(0, Math.min(1, v.position / v.duration));
export const failureOf = (e: unknown): Failure => e instanceof ServiceError ? e.failure : 'NETWORK';
export function validSegment(s: Segment, duration = Infinity) { return Number.isFinite(s.start) && Number.isFinite(s.end) && s.start >= 0 && s.end > s.start && s.end <= duration; }
export function mergeSegments(remote: Segment[], local: Segment[]) { return [...remote.filter(r => !local.some(l => l.typeId === r.typeId)), ...local].sort((a, b) => a.start - b.start); }
