import { afterEach, expect, it, vi } from 'vitest';
import { androidPlatform } from '../src/platform/platform';
afterEach(() => vi.unstubAllGlobals());
function host() {
  const sent: { id: string; method: string; v: number }[] = [];
  const nativeHost = { postMessage: (message: string) => sent.push(JSON.parse(message)), onmessage: undefined as ((e: { data: string }) => void) | undefined };
  vi.stubGlobal('window', { nativeHost }); const platform = androidPlatform();
  return { nativeHost, sent, platform, reply(id: string, value: unknown, v = 1) { nativeHost.onmessage?.({ data: JSON.stringify({ v, id, value }) }); } };
}
it('correlates replies and excludes unknown protocol versions', async () => {
  const h = host(); const a = h.platform.call('storage.get', { key: 'app' }), b = h.platform.call('ready');
  h.reply(h.sent[0].id, 'wrong', 2); h.reply(h.sent[1].id, 'second'); h.reply(h.sent[0].id, 'first');
  expect(await a).toBe('first'); expect(await b).toBe('second'); h.platform.dispose();
});
it('propagates cancellation and ignores late responses', async () => {
  const h = host(), abort = new AbortController(); const request = h.platform.call('http', {}, abort.signal); const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' });
  abort.abort(); await rejected; expect(h.sent[1].method).toBe('cancel'); h.reply(h.sent[0].id, 'late'); h.platform.dispose();
});
it('disposal rejects pending work and removes event subscriptions', async () => {
  const h = host(); const request = h.platform.call('ready'); const rejected = expect(request).rejects.toThrow('Host disposed'); h.platform.dispose(); await rejected;
});
