import type { AnimeMapping, Episode, Segment, SegmentType } from '../domain/models';
import { validSegment } from '../domain/models';
import type { HttpResponse, Platform } from '../platform/platform';
const types: SegmentType[] = ['intro', 'recap', 'outro', 'post_credits'];
export function parseIntroDb(raw: string): Segment[] {
  let data: Record<string, Record<string, number>>; try { data = JSON.parse(raw); } catch { return []; }
  return types.flatMap(type => {
    const s = data[type]; if (!s) return [];
    const millis = (name: string) => Number.isInteger(s[name + '_ms']) && s[name + '_ms'] >= 0 ? s[name + '_ms'] : Number.isFinite(s[name + '_sec']) && s[name + '_sec'] >= 0 ? Math.floor(s[name + '_sec'] * 1000) : NaN;
    const start = millis('start'), end = millis('end'); const segment: Segment = { id: `${type}:${start}:${end}`, type, typeId: type, start, end, source: 'INTRODB' };
    return validSegment(segment) ? [segment] : [];
  }).sort((a, b) => a.start - b.start);
}
export function parseAniSkip(raw: string, duration: number): Segment[] {
  const data = JSON.parse(raw); if (data.found === false) return [];
  if (data.found !== true || !Array.isArray(data.results)) throw new Error('Invalid timestamp response');
  const map: Record<string, SegmentType> = { op: 'intro', ed: 'outro', recap: 'recap', 'mixed-op': 'mixed-op', 'mixed-ed': 'mixed-ed' };
  return data.results.flatMap((r: { skipType: string; skipId: string; interval: { startTime: number; endTime: number } }) => {
    const type = map[r.skipType]; const segment: Segment = { type, typeId: type, id: r.skipId, start: Math.floor(r.interval?.startTime * 1000), end: Math.floor(r.interval?.endTime * 1000), source: 'ANISKIP' };
    return type && validSegment(segment, duration) ? [segment] : [];
  }).sort((a: Segment, b: Segment) => a.start - b.start);
}
export class SegmentService {
  constructor(private platform: Platform) {}
  async intro(imdb: string, episode?: Episode | null, signal?: AbortSignal) {
    if (!/^tt\d{7,8}$/.test(imdb)) return [];
    const query = new URLSearchParams({ imdb_id: imdb, ...(episode ? { season: String(episode.season), episode: String(episode.number) } : { is_movie: 'true' }) });
    const r = await this.platform.call<HttpResponse>('http', { client: 'introdb', url: `https://api.introdb.app/segments?${query}` }, signal);
    if (r.status !== 200) throw new Error('Timestamps unavailable'); return parseIntroDb(r.body);
  }
  async anime(mapping: AnimeMapping, duration: number, signal?: AbortSignal) {
    const url = new URL(`https://api.aniskip.com/v2/skip-times/${mapping.malId}/${mapping.episode}`);
    url.searchParams.set('episodeLength', String(duration / 1000)); ['op', 'ed', 'recap', 'mixed-op', 'mixed-ed'].forEach(t => url.searchParams.append('types', t));
    const r = await this.platform.call<HttpResponse>('http', { client: 'aniskip', url: url.href }, signal);
    if (r.status === 404) return []; if (r.status !== 200) throw new Error('Timestamps unavailable'); return parseAniSkip(r.body, duration);
  }
  async submit(segment: Segment, imdb: string, episode?: Episode | null) {
    if (!types.includes(segment.type) || !validSegment(segment)) throw new Error('Invalid contribution');
    const r = await this.platform.call<HttpResponse>('http', { client: 'introdb', url: 'https://api.introdb.app/submit', method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imdb_id: imdb, ...(episode ? { season: episode.season, episode: episode.number } : { is_movie: true }), segment_type: segment.type === 'post_credits' ? 'post-credits' : segment.type,
        start_sec: segment.start / 1000, end_sec: segment.end / 1000 }) });
    if (r.status < 200 || r.status >= 300) throw new Error('Contribution failed');
  }
}
