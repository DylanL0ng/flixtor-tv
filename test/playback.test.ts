import { createRoot } from 'solid-js';
import { describe, expect, it, vi } from 'vitest';
import { createPlayback } from '../src/features/playback/playback';
import { Repository } from '../src/domain/repository';
import type { Platform, PlatformEvent, PlayerEvent } from '../src/platform/platform';
import type { FlixtorService } from '../src/services/flixtor';
import type { AnimeService } from '../src/services/anime';
import type { SegmentService } from '../src/services/segments';
import { ServiceError } from '../src/domain/models';
const stream = { uri: 'https://fixture.example/video.m3u8', headers: {}, captions: [] };
const detail = { title: { id: '1', path: '/watch/movie/1/fixture', name: 'Fixture' }, episodes: [] };
async function fixture(resolve = vi.fn(async () => stream)) {
  const values = new Map<string, string>(), loads: Record<string, unknown>[] = [];
  let listener: (event: PlatformEvent) => void = () => {}, loadEvent: ((id: string) => void) | undefined;
  const platform: Platform = {
    async call<T>(method: string, args: Record<string, unknown> = {}) {
      if (method === 'storage.get') return (values.get(String(args.key)) || null) as T;
      if (method === 'storage.set') values.set(String(args.key), String(args.value));
      if (method === 'legacy') return {} as T;
      if (method === 'player.load') { loads.push(args); loadEvent?.(String(args.id)); }
      return null as T;
    }, onEvent(fn) { listener = fn; return () => {}; }, dispose() {},
  };
  const repo = new Repository(platform); await repo.initialize();
  const account = vi.fn(async () => 'VIP');
  const flixtor = { playback: resolve, account } as unknown as FlixtorService;
  const anime = { servers: vi.fn(async () => ({ DUB: {} })), playback: vi.fn(async () => stream) };
  let dispose!: () => void, profile = 'default';
  const leave = vi.fn(), accountChanged = vi.fn();
  const player = createRoot(d => { dispose = d; return createPlayback(platform, repo, flixtor, anime as unknown as AnimeService, { intro: async () => [] } as unknown as SegmentService, () => profile, leave, accountChanged); });
  const emit = (id: string, extra: Partial<PlayerEvent> = {}) => listener({ type: 'player', value: { id, position: 30_000, duration: 100_000, buffered: 50_000, playing: false, playRequested: false, buffering: false, ended: false, time: performance.now(), tracks: [], bytes: 0, byteLimit: 1000, ...extra } });
  return { player, repo, loads, emit, account, resolve, leave, accountChanged, anime,
    onLoad(fn: (id: string) => void) { loadEvent = fn; },
    lifecycle(foreground: boolean) { listener({ type: 'lifecycle', value: { foreground } }); },
    profile(value: string) { profile = value; },
    close() { player.dispose(); dispose(); } };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}
describe('silent playback recovery', () => {
  it('refreshes account once after initial stream denial', async () => {
    const resolve = vi.fn().mockRejectedValueOnce(new ServiceError('SIGNED_OUT')).mockResolvedValue(stream); const f = await fixture(resolve);
    try { await f.player.start(detail); expect(f.account).toHaveBeenCalledTimes(1); expect(resolve).toHaveBeenCalledTimes(2); expect(f.loads).toHaveLength(1); expect(f.player.state.error).toBeUndefined(); } finally { f.close(); }
  });
  it('re-resolves media once, preserving paused position and rejecting retired callbacks', async () => {
    const f = await fixture();
    try {
      await f.player.start(detail); const old = String(f.loads[0].id);
      f.emit(old, { error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      await vi.waitFor(() => expect(f.loads).toHaveLength(2));
      expect(f.loads[1].position).toBe(30_000); expect(f.loads[1].play).toBe(false); expect(f.account).toHaveBeenCalledTimes(1);
      f.emit(old, { position: 99_000, playing: true }); expect(f.player.state.event?.position).toBe(30_000);
      f.emit(String(f.loads[1].id), { error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      expect(f.player.state.error).toBe('MEDIA'); expect(f.resolve).toHaveBeenCalledTimes(2);
    } finally { f.close(); }
  });
  it('a late obsolete stream cannot replace current playback', async () => {
    let unblock!: (value: typeof stream) => void;
    const resolve = vi.fn().mockImplementationOnce(() => new Promise<typeof stream>(r => { unblock = r; })).mockResolvedValue(stream);
    const f = await fixture(resolve);
    try {
      const old = f.player.start(detail); await vi.waitFor(() => expect(resolve).toHaveBeenCalledTimes(1));
      await f.player.start({ ...detail, title: { ...detail.title, id: '2', name: 'Current', path: '/watch/movie/2/current' } });
      unblock(stream); await old;
      expect(f.loads).toHaveLength(1); expect(f.player.state.detail?.title.id).toBe('2'); expect(f.account).not.toHaveBeenCalled();
    } finally { f.close(); }
  });
  it('shares the single recovery budget and resets it on explicit retry', async () => {
    const resolve = vi.fn().mockRejectedValueOnce(new ServiceError('SIGNED_OUT')).mockResolvedValue(stream);
    const f = await fixture(resolve);
    try {
      await f.player.start(detail);
      expect(f.accountChanged).toHaveBeenCalledWith('VIP');
      f.emit(String(f.loads[0].id), { error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      expect(f.player.state.error).toBe('MEDIA'); expect(f.account).toHaveBeenCalledTimes(1);
      await f.player.start(detail);
      expect(f.player.state.error).toBeUndefined(); expect(f.player.state.loading).toBe(false);
      expect(f.player.state.id).toBe(String(f.loads[1].id));
      f.emit(String(f.loads[1].id), { error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      expect(f.player.state.loading).toBe(true);
      await vi.waitFor(() => expect({ error: f.player.state.error, loads: f.loads.length, accounts: f.account.mock.calls.length }).toEqual({ error: undefined, loads: 3, accounts: 2 }));
      expect(f.account).toHaveBeenCalledTimes(2); expect(f.player.state.error).toBeUndefined();
    } finally { f.close(); }
  });
  it('latches background pause intent even when returning before refresh completes', async () => {
    const f = await fixture(), gate = deferred<string>();
    f.account.mockReturnValueOnce(gate.promise);
    try {
      await f.player.start(detail);
      const old = String(f.loads[0].id);
      f.emit(old, { position: 99_000, playRequested: true, error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      await vi.waitFor(() => expect(f.account).toHaveBeenCalledTimes(1));
      f.emit(old, { position: 0, playing: true });
      expect(f.player.state.event?.position).toBe(99_000);
      f.lifecycle(false); f.lifecycle(true);
      gate.resolve('VIP');
      await vi.waitFor(() => expect(f.loads).toHaveLength(2));
      expect(f.loads[1].position).toBe(99_000); expect(f.loads[1].play).toBe(false);
      expect(f.player.state.error).toBeUndefined();
    } finally { gate.resolve('VIP'); f.close(); }
  });
  it.each(['stop', 'replace', 'profile', 'dispose'] as const)('does not revive obsolete recovery after %s', async action => {
    const f = await fixture(), gate = deferred<string>();
    f.account.mockReturnValueOnce(gate.promise);
    try {
      await f.player.start(detail);
      f.emit(String(f.loads[0].id), { playRequested: true, error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      await vi.waitFor(() => expect(f.account).toHaveBeenCalledTimes(1));
      if (action === 'stop') await f.player.stop();
      if (action === 'replace') await f.player.start({ ...detail, title: { ...detail.title, id: '2' } });
      if (action === 'profile') f.profile('second');
      if (action === 'dispose') f.player.dispose();
      const loads = f.loads.length, calls = f.resolve.mock.calls.length;
      gate.resolve('VIP'); await new Promise(r => setTimeout(r, 0));
      expect(f.loads).toHaveLength(loads); expect(f.resolve).toHaveBeenCalledTimes(calls);
      expect(f.accountChanged).not.toHaveBeenCalled(); expect(f.player.state.error).toBeUndefined();
    } finally { gate.resolve('VIP'); f.close(); }
  });
  it('rejects obsolete recovery before delayed progress persistence finishes', async () => {
    const f = await fixture(), gate = deferred<void>();
    try {
      await f.player.start(detail);
      vi.spyOn(f.repo, 'progress').mockReturnValueOnce(gate.promise);
      f.emit(String(f.loads[0].id), { error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' });
      await f.player.start({ ...detail, title: { ...detail.title, id: '2' } });
      gate.resolve(); await new Promise(r => setTimeout(r, 0));
      expect(f.player.state.detail?.title.id).toBe('2'); expect(f.loads).toHaveLength(2);
      expect(f.account).not.toHaveBeenCalled();
    } finally { gate.resolve(); f.close(); }
  });
  it('claims a new start before awaiting previous progress and never stops its replacement', async () => {
    const f = await fixture(), gate = deferred<void>();
    try {
      await f.player.start(detail); f.emit(String(f.loads[0].id));
      vi.spyOn(f.repo, 'progress').mockReturnValueOnce(gate.promise);
      const old = f.player.start({ ...detail, title: { ...detail.title, id: '2' } });
      await f.player.start({ ...detail, title: { ...detail.title, id: '3' } });
      gate.resolve(); await old;
      expect(f.player.state.detail?.title.id).toBe('3'); expect(f.loads).toHaveLength(2);
      expect(f.resolve).toHaveBeenCalledTimes(2);
    } finally { gate.resolve(); f.close(); }
  });
  it('shows the final refresh failure without looping', async () => {
    const resolve = vi.fn().mockRejectedValue(new ServiceError('SIGNED_OUT')), f = await fixture(resolve);
    f.account.mockRejectedValueOnce(new ServiceError('UNAVAILABLE'));
    try {
      await f.player.start(detail);
      expect(f.player.state.error).toBe('UNAVAILABLE'); expect(f.player.state.loading).toBe(false);
      expect(resolve).toHaveBeenCalledTimes(1); expect(f.account).toHaveBeenCalledTimes(1);
    } finally { f.close(); }
  });
  it('recovers native errors received before the load acknowledgement', async () => {
    const f = await fixture(); let failed = false;
    f.onLoad(id => { if (!failed) { failed = true; f.emit(id, { playRequested: true, error: 'ERROR_CODE_IO_BAD_HTTP_STATUS' }); } });
    try {
      await f.player.start(detail);
      expect(f.loads).toHaveLength(2); expect(f.account).toHaveBeenCalledTimes(1);
      expect(f.loads[1].play).toBe(true); expect(f.player.state.error).toBeUndefined();
    } finally { f.close(); }
  });
  it('does not use the FlixTor account fallback for anime failures', async () => {
    const f = await fixture();
    f.anime.playback.mockRejectedValueOnce(new ServiceError('UNAVAILABLE'));
    try {
      await f.player.start({ title: { ...detail.title, source: 'HIANIME' }, episodes: [] }, { season: 1, number: 1, name: 'Episode' }, 'DUB');
      expect(f.player.state.error).toBe('UNAVAILABLE'); expect(f.account).not.toHaveBeenCalled();
    } finally { f.close(); }
  });
});
