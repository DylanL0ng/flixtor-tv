import { createServer } from 'node:http';
import { PassThrough } from 'node:stream';
import type { AddressInfo } from 'node:net';
import type { ViteDevServer } from 'vite';
import { afterEach, expect, it, vi } from 'vitest';
import { browserBridge, publicAddress, publicUrl, rewritePlaylist } from '../tools/browser-bridge';
import { parseStream, USER_AGENT } from '../src/services/flixtor';

const { upstream } = vi.hoisted(() => ({ upstream: vi.fn() }));
vi.mock('node:dns/promises', () => ({ lookup: async () => [{ address: '8.8.8.8', family: 4 }] }));
vi.mock('node:https', () => ({ request: upstream }));
afterEach(() => upstream.mockReset());

async function bridge() {
  upstream.mockImplementation((url, options, receive) => {
    const call = new PassThrough();
    Object.assign(call, { setTimeout: () => call });
    call.on('finish', () => {
      const response = Object.assign(new PassThrough(), { statusCode: 200,
        headers: { 'content-type': 'text/html', ...(url.pathname === '/home' ? { 'set-cookie': ['session=live-secret; Secure; HttpOnly; Path=/'] } : {}) },
        socket: { setTimeout() {} } });
      receive(response); response.end('<p>upstream content</p>');
    });
    return call;
  });
  const server = createServer();
  const configure = browserBridge().configureServer as (server: ViteDevServer) => void;
  configure({ httpServer: server, middlewares: { use(fn: (req: unknown, res: unknown, next: () => void) => void) {
    server.on('request', (req, res) => fn(req, res, () => { res.writeHead(404); res.end(); }));
  } } } as unknown as ViteDevServer);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  async function call(method: string, args = {}, cookie = '') {
    const response = await fetch(base + '/__platform/call', { method: 'POST', headers: {
      'Content-Type': 'application/json', 'X-Flixtor-Request': '1', ...(cookie ? { Cookie: cookie } : {}),
    }, body: JSON.stringify({ method, args }) });
    return { response, json: await response.json() };
  }
  const first = await call('session.open'); const cookie = first.response.headers.get('set-cookie')!.split(';')[0];
  return { base, cookie, call, close: () => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); }) };
}

it('uses upstream data, keeps cookies scoped and isolates browser sessions', async () => {
  const b = await bridge();
  try {
    const response = await b.call('http', { client: 'flixtor', url: 'https://flixtor.to/home' }, b.cookie);
    expect(response.json.value.body).toBe('<p>upstream content</p>');
    expect(JSON.stringify(response.json)).not.toContain('live-secret');
    await b.call('http', { client: 'flixtor', url: 'https://flixtor.to/account' }, b.cookie);
    expect(upstream.mock.calls[1][1].headers.Cookie).toBe('session=live-secret');
    await b.call('http', { client: 'anime', url: 'https://hianime.at/most-popular' }, b.cookie);
    expect(upstream.mock.calls[2][1].headers.Cookie).toBeUndefined();
    const second = await b.call('session.open'); const cookie = second.response.headers.get('set-cookie')!.split(';')[0];
    await b.call('http', { client: 'flixtor', url: 'https://flixtor.to/account' }, cookie);
    expect(upstream.mock.calls[3][1].headers.Cookie).toBeUndefined();
    await b.call('session.clear', {}, b.cookie);
    await b.call('http', { client: 'flixtor', url: 'https://flixtor.to/account' }, b.cookie);
    expect(upstream.mock.calls[4][1].headers.Cookie).toBeUndefined();
  } finally { await b.close(); }
});

it('rejects wrong client scopes and cross-site calls without contacting providers', async () => {
  const b = await bridge();
  try {
    expect((await b.call('http', { client: 'flixtor', url: 'https://other.example/home' }, b.cookie)).response.status).toBe(502);
    const denied = await fetch(b.base + '/__platform/call', { method: 'POST', headers: { Origin: 'https://other.example' }, body: '{}' });
    expect(denied.status).toBe(403); expect(upstream).not.toHaveBeenCalled();
  } finally { await b.close(); }
});

it('sends the FlixTor user agent through master playlists, variants and segments', async () => {
  const b = await bridge();
  upstream.mockImplementation((url, options, receive) => {
    const call = new PassThrough(); Object.assign(call, { setTimeout: () => call });
    call.on('finish', () => {
      const playlist = url.pathname.endsWith('.m3u8');
      const allowed = options.headers['User-Agent'] === USER_AGENT && options.headers.Referer === 'https://flixtor.to/';
      const response = Object.assign(new PassThrough(), { statusCode: allowed ? 200 : 500,
        headers: { 'content-type': playlist ? 'application/x-mpegURL' : 'video/mp2t' }, socket: { setTimeout() {} } });
      receive(response);
      response.end(allowed ? url.pathname === '/master.m3u8' ? '#EXTM3U\nvariant.m3u8?token=signed' : playlist ? '#EXTM3U\nsegment.ts?token=signed' : 'segment bytes' : '');
    });
    return call;
  });
  try {
    const stream = parseStream('{"file":"https://cdn.example/master.m3u8"}');
    const media = await b.call('media', { ...stream, client: 'flixtor' }, b.cookie);
    let uri = media.json.value.uri;
    for (let step = 0; step < 3; step++) {
      const response = await fetch(b.base + uri, { headers: { Cookie: b.cookie } });
      expect(response.status).toBe(200);
      const body = await response.text();
      if (step < 2) { expect(body).toContain('/__platform/asset/'); uri = body.split('\n')[1]; }
      else expect(body).toBe('segment bytes');
    }
    expect(upstream.mock.calls.map(([url]) => url.pathname + url.search)).toEqual(['/master.m3u8', '/variant.m3u8?token=signed', '/segment.ts?token=signed']);
  } finally { await b.close(); }
});

it('rejects private destinations, including mapped IPv6 and unusual URL encodings', () => {
  for (const ip of ['127.0.0.1', '10.0.0.1', '192.168.1.1', '169.254.169.254', '100.64.0.1', '::1', 'fc00::1', '::ffff:127.0.0.1']) expect(publicAddress(ip), ip).toBe(false);
  expect(publicAddress('8.8.8.8')).toBe(true); expect(publicAddress('2606:4700:4700::1111')).toBe(true);
  for (const url of ['http://example.com', 'https://127.0.0.1', 'https://2130706433', 'https://[::1]', 'https://user:pass@example.com', 'https://example.com:8443']) expect(() => publicUrl(url), url).toThrow();
});

it('rewrites HLS variants, encryption keys, segments and subtitle URLs against the upstream URL', () => {
  const source = '#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,URI="../subs/en.m3u8"\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXT-X-MAP:URI="init.mp4"\n720/index.m3u8?token=abc\n';
  const result = rewritePlaylist(source, 'https://cdn.example/show/master.m3u8', url => '/proxy?url=' + encodeURIComponent(url));
  for (const url of ['https://cdn.example/subs/en.m3u8', 'https://cdn.example/show/key.bin', 'https://cdn.example/show/init.mp4', 'https://cdn.example/show/720/index.m3u8?token=abc']) expect(result).toContain(encodeURIComponent(url));
  expect(() => rewritePlaylist('#EXTM3U\nhttp://localhost/private', 'https://cdn.example/master.m3u8', value => value)).toThrow();
});
