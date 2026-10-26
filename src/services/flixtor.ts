import { all, attr, document, one, text, unique } from './html';
import { ServiceError, titleKey, type Account, type Caption, type Detail, type Failure, type Shelf, type Stream, type Title, type Episode } from '../domain/models';
import type { HttpResponse, Platform } from '../platform/platform';
export const BASE = 'https://flixtor.to';
export const USER_AGENT = 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
export function secureUrl(value: string, base?: string) {
  const url = new URL(value, base);
  if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443' || !url.hostname.includes('.') || url.hostname.endsWith('.local') || /^[\d.]+$/.test(url.hostname) || url.hostname.includes(':')) throw new ServiceError('CHANGED');
  return url.href;
}
export function safePath(path: string) {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\') || new URL(path, BASE).origin !== BASE) throw new ServiceError('CHANGED');
  return path;
}
export function responseFailure(raw: string, status = 200): Failure | undefined {
  let message = '';
  try {
    const data = JSON.parse(raw);
    if (data.file && status >= 200 && status < 300) { try { secureUrl(data.file); return; } catch { /* classify denial */ } }
    message = [data.error, data.message, data.reason, data.status].join(' ').toLowerCase();
  } catch {
    const doc = document(raw); const content = text(one(doc, '#content')).toLowerCase();
    if (status >= 200 && status < 300 && content.includes('all our servers are full') && content.includes('cannot accept additional users')) return 'CAPACITY';
    if (status >= 200 && status < 300 && one(doc, '.cflip, meta[property="og:type"]')) return;
    message = text(doc).toLowerCase().slice(0, 4096);
  }
  if (status === 429 || status >= 500) return 'UNAVAILABLE';
  if (message.includes('all our servers are full') && message.includes('cannot accept additional users')) return 'CAPACITY';
  if (/account (?:is )?blocked/.test(message)) return 'BLOCKED';
  if (/(session|login) expired/.test(message)) return 'EXPIRED';
  if (status === 401 || /please (?:sign in|log in|login)|you must be logged in|(?:sign|log) in to watch|login required|not logged in|<title>sign in/.test(message + raw.toLowerCase().match(/<title>sign in/)?.[0])) return 'SIGNED_OUT';
  if (status === 443 || /vip required|vip only|only available to vip|outside the free|free access limit|vip members|active vip/.test(message)) return 'VIP';
  if (status === 403 || /access denied|verify your browser|cf-chl-/.test(message + raw)) return 'ACCESS';
  if (status < 200 || status >= 300) return 'NETWORK';
}
function check(raw: string, status = 200) { const failure = responseFailure(raw, status); if (failure) throw new ServiceError(failure); }
export function parseCards(html: string): Title[] {
  check(html); const doc = document(html);
  const cards = all(doc, '.cflip[data-href]').flatMap(card => {
    const path = attr(card, 'data-href'); const match = /^\/watch\/(movie|tv)\/(\d+)\/[^?#]+/.exec(path);
    if (!match) return [];
    try { safePath(path); } catch { return []; }
    const image = one(card, '.front img, img'); const name = text(one(card, '.front .card-footer')) || attr(image, 'alt');
    if (!name) return [];
    let poster = ''; try { if (attr(image, 'src')) poster = secureUrl(attr(image, 'src'), BASE); } catch { /* optional artwork */ }
    return [{ id: match[2], path, name, poster, year: text(one(card, '.front .card-badge.top .badge')),
      rating: text(one(card, '.front .card-badge.bottom .badge')), genres: text(one(card, '.genres')), source: 'FLIXTOR' as const }];
  });
  if (!cards.length && !/no results/i.test(text(one(doc, '.contentList')))) throw new ServiceError('CHANGED');
  return unique(cards, titleKey);
}
export function parseHome(html: string): Shelf[] {
  check(html); const doc = document(html);
  const shelves = [['contentListR', 'Popular'], ['contentListM', 'Movies'], ['contentListT', 'TV shows'], ['contentListE', 'Latest episodes']].flatMap(([css, label]) => {
    const node = one(doc, '.' + css); if (!node) return [];
    // Selectors run against original fragment without inserting it into the browser document.
    const cards = all(node, '.cflip[data-href]');
    const titles = cards.flatMap(c => { try { return parseCards((awaitlessHtml(c))); } catch { return []; } });
    return titles.length ? [{ label, titles }] : [];
  });
  if (!shelves.length) throw new ServiceError('CHANGED'); return shelves;
}
import { getOuterHTML as awaitlessHtml } from 'domutils';
export function parseDetail(html: string, original: Title): Detail {
  check(html); const doc = document(html); if (!one(doc, 'meta[property="og:type"]')) throw new ServiceError('CHANGED');
  const candidates = all(doc, 'a[href*="imdb.com"], [data-imdb], [data-imdb-id], meta[content*="imdb.com"]');
  const imdb = candidates.map(n => Object.values(n.attribs).join(' ')).join(' ').match(/tt\d{7,8}/)?.[0] || original.imdb;
  return { title: { ...original, poster: attr(one(doc, 'meta[property="og:image"]'), 'content') || original.poster,
    synopsis: attr(one(doc, 'meta[name="description"]'), 'content'), imdb,
    backdrop: /background-image\s*:\s*url\(['"]?(https:\/\/[^)'"\s]+)/.exec(html)?.[1] || '' },
    episodes: unique(all(doc, '.eplist[data-pes][data-pep]').flatMap(n => {
      const season = Number(attr(n, 'data-pes')), number = Number(attr(n, 'data-pep'));
      return Number.isInteger(season) && Number.isInteger(number) ? [{ season, number, name: text(one(n, '.epTitle')) }] : [];
    }), e => `${e.season}:${e.number}`) };
}
export function parseAccount(html: string): Account {
  const doc = document(html); if (!one(doc, '.logout')) return 'GUEST';
  const badges = all(doc, '[data-vip-status], .account-status, .vip-status, .acct-status');
  if (badges.some(n => attr(n, 'data-vip-status').toLowerCase() === 'expired' || /\b(?:VIP\s+)?expired\b/i.test(text(n)))) return 'EXPIRED';
  if (badges.some(n => attr(n, 'data-vip-status').toLowerCase() === 'active' || /^VIP active$/i.test(text(n)))) return 'VIP';
  return 'SIGNED_IN';
}
export function decodePlayback(raw: string) {
  if (raw.trimStart().startsWith('{')) return raw;
  const rotated = raw.trim().replace(/[a-zA-Z]/g, c => String.fromCharCode(c.charCodeAt(0) + (c.toLowerCase() <= 'm' ? 13 : -13)));
  const decoded = new TextDecoder().decode(Uint8Array.from(atob(rotated), c => c.charCodeAt(0)));
  return [...decoded].map(c => c.charCodeAt(0) >= 33 && c.charCodeAt(0) <= 126 ? String.fromCharCode(33 + (c.charCodeAt(0) + 14) % 94) : c).join('');
}
export function parseStream(raw: string): Stream {
  check(raw);
  try {
    const decoded = decodePlayback(raw); check(decoded); const data = JSON.parse(decoded);
    const uri = secureUrl(data.file);
    const captions: Caption[] = (Array.isArray(data.tracks) ? data.tracks : []).slice(0, 64).flatMap((t: Record<string, unknown> | null) => {
      if (!t || !['captions', 'subtitles'].includes(String(t.kind ?? 'captions')) || !t.file) return [];
      try { const uri = secureUrl(String(t.file), BASE); return [{ uri, label: String(t.label ?? ''), mime: new URL(uri).pathname.endsWith('.srt') ? 'application/x-subrip' : 'text/vtt', default: t.default === true }]; } catch { return []; }
    });
    return { uri, captions, headers: { Referer: BASE + '/', 'User-Agent': USER_AGENT } };
  } catch (e) { if (e instanceof ServiceError) throw e; throw new ServiceError('CHANGED'); }
}
export class FlixtorService {
  private csrf = ''; private bootstrapTask?: Promise<void>;
  constructor(private platform: Platform) {}
  private async raw(path: string, signal?: AbortSignal, data?: unknown, redirects = 0): Promise<string> {
    if (redirects > 5) throw new ServiceError('CHANGED');
    const r = await this.platform.call<HttpResponse>('http', { client: 'flixtor', url: BASE + safePath(path), method: data === undefined ? 'GET' : 'POST',
      headers: { 'User-Agent': USER_AGENT, Referer: BASE + '/home', 'X-Requested-With': 'XMLHttpRequest', ...(data === undefined ? {} : { Origin: BASE, 'X-CSRF-Token': this.csrf, 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }) },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }) }, signal);
    if ([301, 302, 303, 307, 308].includes(r.status)) {
      const next = new URL(r.location || '', BASE + path); if (next.origin !== BASE) throw new ServiceError('ACCESS');
      return this.raw(next.pathname + next.search, signal, [307, 308].includes(r.status) ? data : undefined, redirects + 1);
    }
    const failure = responseFailure(r.body, r.status); if (failure && failure !== 'CAPACITY') throw new ServiceError(failure);
    return r.body;
  }
  private async bootstrap(signal?: AbortSignal) {
    if (this.csrf) return;
    const previous = this.bootstrapTask; let release!: () => void;
    this.bootstrapTask = new Promise<void>(resolve => { release = resolve; });
    try {
      await previous; signal?.throwIfAborted(); if (this.csrf) return;
      const html = await this.raw('/home', signal); signal?.throwIfAborted();
      this.csrf = attr(one(document(html), 'meta[name="csrf-token"]'), 'content');
      if (!this.csrf) throw new ServiceError('ACCESS');
    } finally { release(); }
  }
  async home(signal?: AbortSignal) { await this.bootstrap(signal); return parseHome(await this.raw('/home', signal)); }
  async browse(kind: 'movies' | 'tvshows', page: number, sort: string, signal?: AbortSignal) {
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !['latest', 'best', 'name'].includes(sort)) throw new ServiceError('CHANGED');
    await this.bootstrap(signal); return parseCards(await this.raw(`/show/${kind}?ajax=1&page=${page}&sort=${sort}`, signal));
  }
  async search(query: string, page: number, signal?: AbortSignal) {
    if (!query.trim() || query.length > 64) return []; await this.bootstrap(signal);
    return parseCards(await this.raw(`/search/${encodeURIComponent(query)}?ajax=1&page=${page}`, signal));
  }
  async detail(t: Title, signal?: AbortSignal) { await this.bootstrap(signal); return parseDetail(await this.raw(t.path, signal), t); }
  async account(signal?: AbortSignal): Promise<Account> {
    await this.bootstrap(signal); const html = await this.raw('/home', signal);
    this.csrf = attr(one(document(html), 'meta[name="csrf-token"]'), 'content') || this.csrf;
    const account = parseAccount(html); return account === 'SIGNED_IN' ? parseAccount(await this.raw('/account', signal)) : account;
  }
  async login(email: string, password: string, signal?: AbortSignal) { await this.bootstrap(signal); await this.raw('/ajax/viplogin', signal, { uname: email.trim(), passw: password }); const account = await this.account(signal); if (!['VIP', 'SIGNED_IN'].includes(account)) throw new ServiceError('VIP'); return account; }
  async pair(signal?: AbortSignal): Promise<{ code: string; url: string }> { await this.bootstrap(signal); const data = JSON.parse(await this.raw('/ajax/pair/new', signal, {})); const url = new URL(data.url); if (url.origin !== BASE || !url.pathname.startsWith('/pair/')) throw new ServiceError('CHANGED'); return { code: data.code, url: url.href }; }
  async poll(code: string, signal?: AbortSignal) { const data = JSON.parse(await this.raw('/ajax/pair/poll', signal, { code })); if (data.expired || data.state === 'expired') throw new ServiceError('EXPIRED'); if (data.state === 'blocked') throw new ServiceError('BLOCKED'); if (!['pending', 'done'].includes(data.state)) throw new ServiceError('CHANGED'); return String(data.state); }
  async playback(t: Title, e?: Episode | null, signal?: AbortSignal) { if (!/^\d+$/.test(t.id)) throw new ServiceError('CHANGED'); await this.bootstrap(signal); return parseStream(await this.raw(e ? `/ajax/v4/e/${t.id}/${e.season}/${e.number}` : `/ajax/v4/m/${t.id}`, signal)); }
  async clear() { this.csrf = ''; await this.platform.call('session.clear'); }
}
