import fixtures from './fixtures/http.json';
import type { Platform, PlatformEvent, PlayerEvent } from '../src/platform/platform';

/** Development fixtures only. Production never substitutes mock networking. */
export function fixturePlatform(): Platform {
  const listeners = new Set<(event: PlatformEvent) => void>();
  let player: PlayerEvent = { id: '', position: 0, duration: 120_000, buffered: 120_000, playing: false, playRequested: false, buffering: false, ended: false, time: performance.now(), tracks: [], bytes: 0, byteLimit: 1_000_000 };
  const timer = setInterval(() => {
    const now = performance.now(); if (player.playing) player.position = Math.min(player.duration, player.position + now - player.time);
    player = { ...player, time: now, ended: player.position >= player.duration };
    if (player.ended) player.playing = false;
    if (player.id) listeners.forEach(fn => fn({ type: 'player', value: { ...player } }));
  }, 250);
  async function digest(profile: string, pin: string) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${profile}:${pin}`)))].map(b => b.toString(16).padStart(2, '0')).join(''); }
  async function call<T>(method: string, args: Record<string, unknown> = {}, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
    let value: unknown = null;
    if (method === 'ready') value = { platform: 'browser-fixture', version: 'development', versionCode: 2, sdk: 34, commit: 'fixture', reportUrl: '', contribution: false };
    else if (method === 'storage.get') value = localStorage.getItem('flixtor-fixture:' + args.key);
    else if (method === 'storage.set') localStorage.setItem('flixtor-fixture:' + args.key, String(args.value));
    else if (method === 'storage.remove') localStorage.removeItem('flixtor-fixture:' + args.key);
    else if (method === 'legacy') value = {};
    else if (method === 'input') value = window.prompt(String(args.label), String(args.value || ''));
    else if (method === 'credentials.verify') { const stored = localStorage.getItem(`flixtor-fixture:pin:${args.profile}`); value = !stored || stored === await digest(String(args.profile), String(args.pin)); }
    else if (method === 'credentials.commit') { localStorage.setItem(`flixtor-fixture:pin:${args.profile}`, args.pin ? await digest(String(args.profile), String(args.pin)) : ''); localStorage.setItem('flixtor-fixture:app', String(args.value)); }
    else if (method === 'http') {
      const url = new URL(String(args.url)); const path = url.pathname;
      const entries = fixtures as Record<string, string>;
      const body = args.client === 'anime' ? path.includes('/episode/list/') ? fixtures.animeEpisodes : path.includes('/episode/servers') ? fixtures.animeServers : path.includes('/mal/') ? fixtures.animeEmbed : path === '/most-popular' || path === '/search' ? fixtures.anime : fixtures.animeDetail : entries[path] ?? (path.startsWith('/ajax/v4/') ? fixtures.stream : path.startsWith('/watch/') ? fixtures.detail : path.startsWith('/show/') || args.client === 'flixtor' && path.startsWith('/search/') ? fixtures.catalog : '{}');
      value = { status: 200, body, location: '' };
    } else if (method === 'player.load') player = { ...player, id: String(args.id), position: Number(args.position || 0), playing: args.play !== false, playRequested: args.play !== false, error: undefined, ended: false, time: performance.now() };
    else if (method === 'player.command' && args.id === player.id) {
      if (args.action === 'seek') player.position = Number(args.position);
      if (args.action === 'play' || args.action === 'pause') player.playing = player.playRequested = args.action === 'play';
      if (args.action === 'stop') player = { ...player, id: '', playing: false, playRequested: false };
    } else if (method === 'art') value = '';
    return value as T;
  }
  return { call, onEvent(fn) { listeners.add(fn); return () => listeners.delete(fn); }, dispose() { clearInterval(timer); listeners.clear(); } };
}
