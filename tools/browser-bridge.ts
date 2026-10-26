import { randomUUID } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { BlockList, isIP } from 'node:net';
import { request } from 'node:https';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { CookieJar } from 'tough-cookie';
import type { Plugin } from 'vite';
import type { HttpRequest } from '../src/platform/platform.ts';

const PREFIX = '/__platform';
const LIMIT = 8 * 1024 * 1024;
const privateAddresses = new BlockList();
for (const [ip, bits] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.168.0.0', 16], ['198.18.0.0', 15], ['224.0.0.0', 4], ['240.0.0.0', 4]] as const) privateAddresses.addSubnet(ip, bits);
const globalV6 = new BlockList(); globalV6.addSubnet('2000::', 3, 'ipv6');
privateAddresses.addSubnet('2001:db8::', 32, 'ipv6');
export function publicAddress(address: string) {
  return isIP(address) === 4 ? !privateAddresses.check(address) : isIP(address) === 6 && globalV6.check(address, 'ipv6') && !privateAddresses.check(address, 'ipv6');
}
export function publicUrl(value: string) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.port && url.port !== '443' || url.username || url.password ||
      !url.hostname.includes('.') || url.hostname.endsWith('.local') || isIP(url.hostname) || url.hostname.includes(':')) throw new Error('ACCESS');
  return url;
}
function scopeUrl(value: string, client: HttpRequest['client']) {
  const url = publicUrl(value);
  const hosts: Partial<Record<HttpRequest['client'], string>> = { flixtor: 'flixtor.to', introdb: 'api.introdb.app', aniskip: 'api.aniskip.com', updates: 'github.com' };
  const host = hosts[client];
  if (!['flixtor', 'anime', 'introdb', 'aniskip', 'updates', 'public'].includes(client) || host && url.hostname !== host) throw new Error('ACCESS');
  return url;
}
function safeHeaders(value: Record<string, string> = {}) {
  const headers: Record<string, string> = { 'Accept-Encoding': 'identity' };
  for (const [key, text] of Object.entries(value)) {
    if (!['cookie', 'host', 'authorization', 'set-cookie', 'connection', 'content-length', 'transfer-encoding'].includes(key.toLowerCase()) && typeof text === 'string') headers[key] = text;
  }
  return headers;
}
async function bounded(stream: IncomingMessage, limit = LIMIT) {
  let length = 0; const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    length += chunk.length; if (length > limit) { stream.destroy(); throw new Error('Response too large'); }
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}
interface Asset { client: HttpRequest['client']; headers: Record<string, string> }
interface Session { jar: CookieJar; assets: Map<string, Asset>; touched: number; calls: Set<AbortController> }
export function rewritePlaylist(text: string, base: string, asset: (url: string) => string) {
  const rewrite = (value: string) => value.startsWith('data:') ? value : asset(publicUrl(new URL(value, base).href).href);
  return text.split(/\r?\n/).map(line => line.startsWith('#') ? line.replace(/URI="([^"]+)"/g, (_, url: string) => `URI="${rewrite(url)}"`) : line.trim() ? rewrite(line.trim()) : line).join('\n');
}

/** Loopback-only development transport. Service cookies never enter browser storage or JS. */
export function browserBridge(): Plugin {
  const sessions = new Map<string, Session>();
  async function open(url: URL, args: HttpRequest, session: Session, signal: AbortSignal): Promise<IncomingMessage> {
    const addresses = await lookup(url.hostname, { all: true });
    if (!addresses.length || addresses.some(a => !publicAddress(a.address))) throw new Error('ACCESS');
    const headers = safeHeaders(args.headers);
    if (args.client === 'flixtor') {
      const cookie = await session.jar.getCookieString(url.href); if (cookie) headers.Cookie = cookie;
    }
    return new Promise((resolve, reject) => {
      const call = request(url, { method: args.method || 'GET', headers, signal,
        lookup(_host, options, callback) {
          if (options.all) callback(null, addresses);
          else callback(null, addresses[0].address, addresses[0].family);
        },
      }, response => resolve(response));
      call.setTimeout(30_000, () => call.destroy(new Error('NETWORK')));
      call.on('error', reject);
      call.end(args.method === 'POST' ? args.body || '' : undefined);
    });
  }
  async function upstream(value: string, args: HttpRequest, session: Session, signal: AbortSignal): Promise<{ response: IncomingMessage; url: URL }> {
    const url = publicUrl(value), response = await open(url, args, session, signal);
    if (args.client === 'flixtor' && !signal.aborted) {
      for (const cookie of response.headers['set-cookie'] || []) await session.jar.setCookie(cookie, url.href, { ignoreError: true });
    }
    // API callers handle redirects themselves; media/artwork need a resolved body.
    return { response, url };
  }
  return {
    name: 'live-browser-bridge', apply: 'serve',
    configureServer(server) {
      server.httpServer?.once('close', () => {
        for (const session of sessions.values()) for (const call of session.calls) call.abort();
        sessions.clear();
      });
      server.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith(PREFIX + '/')) { next(); return; }
        void handle(req, res).catch(() => {
          if (res.headersSent) res.destroy();
          else { res.statusCode = 502; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ error: 'NETWORK' })); }
        });
      });
    },
  };
  async function handle(req: IncomingMessage, res: ServerResponse) {
    const local = new URL(req.url!, `http://${req.headers.host}`);
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(local.hostname) || req.headers.origin && req.headers.origin !== local.origin || req.headers['sec-fetch-site'] === 'cross-site') {
      res.writeHead(403); res.end(); return;
    }
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    const now = Date.now();
    for (const [id, item] of sessions) if (now - item.touched > 24 * 60 * 60_000) { for (const call of item.calls) call.abort(); sessions.delete(id); }
    let id = /(?:^|;\s*)flixtor_session=([\w-]+)/.exec(req.headers.cookie || '')?.[1];
    let session = id ? sessions.get(id) : undefined;
    if (!session) {
      if (sessions.size >= 32) { res.writeHead(503); res.end(); return; }
      id = randomUUID(); session = { jar: new CookieJar(), assets: new Map(), touched: now, calls: new Set() }; sessions.set(id, session);
      res.setHeader('Set-Cookie', `flixtor_session=${id}; Path=${PREFIX}; HttpOnly; SameSite=Strict`);
    }
    session.touched = now;
    if (session.calls.size >= 64) { res.writeHead(429); res.end(); return; }
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 45_000);
    session.calls.add(controller);
    const disconnected = () => { if (!res.writableEnded) controller.abort(); };
    res.once('close', disconnected);
    try {
      if (req.method === 'POST' && local.pathname === PREFIX + '/call') {
        if (req.headers['x-flixtor-request'] !== '1') { res.writeHead(403); res.end(); return; }
        const { method, args = {} } = JSON.parse((await bounded(req, 2_000_000)).toString());
        let value: unknown;
        if (method === 'session.open') value = true;
        else if (method === 'http') {
          const input = args as HttpRequest, url = scopeUrl(input.url, input.client);
          if (input.method && !['GET', 'POST'].includes(input.method)) throw new Error('ACCESS');
          const result = await upstream(url.href, input, session, controller.signal);
          value = { status: result.response.statusCode, location: result.response.headers.location || '', body: (await bounded(result.response)).toString() };
        } else if (method === 'session.clear') {
          for (const call of session.calls) if (call !== controller) call.abort();
          await session.jar.removeAllCookies(); session.assets.clear(); value = true;
        } else if (method === 'art' || method === 'media') {
          publicUrl(method === 'art' ? args.url : args.uri);
          const ticket = randomUUID();
          session.assets.set(ticket, { client: method === 'art' ? 'public' : args.client === 'flixtor' ? 'flixtor' : 'anime', headers: safeHeaders(args.headers) });
          while (session.assets.size > 256) session.assets.delete(session.assets.keys().next().value!);
          const asset = (url: string) => `${PREFIX}/asset/${ticket}?url=${encodeURIComponent(publicUrl(url).href)}`;
          value = method === 'art' ? asset(args.url) : { uri: asset(args.uri), captions: (args.captions || []).slice(0, 64).map((caption: { uri: string }) => ({ ...caption, uri: asset(caption.uri) })) };
        } else throw new Error('Unknown browser capability');
        controller.signal.throwIfAborted(); res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ value }));
      } else if (req.method === 'GET' && local.pathname.startsWith(PREFIX + '/asset/')) {
        res.setHeader('Content-Security-Policy', "default-src 'none'; sandbox");
        const ticket = local.pathname.slice((PREFIX + '/asset/').length), asset = session.assets.get(ticket);
        if (!asset) { res.writeHead(404); res.end(); return; }
        const headers = { ...asset.headers, ...(req.headers.range ? { Range: req.headers.range } : {}) };
        let target = local.searchParams.get('url') || '', result: Awaited<ReturnType<typeof upstream>> | undefined;
        for (let redirects = 0; redirects <= 5; redirects++) {
          result = await upstream(target, { client: asset.client, url: target, headers }, session, controller.signal);
          if (![301, 302, 303, 307, 308].includes(result.response.statusCode || 0)) break;
          const location = result.response.headers.location; result.response.destroy();
          if (!location || redirects === 5) throw new Error('NETWORK');
          target = publicUrl(new URL(location, target).href).href;
        }
        const { response, url } = result!;
        const type = String(response.headers['content-type'] || 'application/octet-stream');
        res.statusCode = response.statusCode || 502;
        if (res.statusCode === 200 && (/mpegurl/i.test(type) || url.pathname.endsWith('.m3u8'))) {
          res.setHeader('Content-Type', 'application/vnd.apple.mpegurl');
          res.end(rewritePlaylist((await bounded(response)).toString(), url.href, next => `${PREFIX}/asset/${ticket}?url=${encodeURIComponent(next)}`));
        } else if (res.statusCode === 200 && (url.pathname.endsWith('.srt') || /subrip/i.test(type))) {
          res.setHeader('Content-Type', 'text/vtt; charset=utf-8');
          res.end('WEBVTT\n\n' + (await bounded(response)).toString().replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2'));
        } else {
          clearTimeout(timeout); response.socket?.setTimeout(0);
          for (const key of ['content-type', 'content-length', 'content-range', 'accept-ranges']) if (response.headers[key]) res.setHeader(key, response.headers[key]!);
          await new Promise<void>((resolve, reject) => {
            response.on('error', reject); res.once('finish', resolve); res.once('close', resolve); response.pipe(res);
          });
        }
      } else { res.writeHead(404); res.end(); }
    } finally { clearTimeout(timeout); session.calls.delete(controller); res.removeListener('close', disconnected); }
  }
}
