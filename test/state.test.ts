import { describe, expect, it } from 'vitest';
import { importLegacy, Repository } from '../src/domain/repository';
import { Countdown, timelineStep, WatchingSession } from '../src/features/playback/timing';
import type { Platform } from '../src/platform/platform';
import { mergeSegments, type Segment } from '../src/domain/models';
function storage(fail = false) {
  const values = new Map<string, string>();
  const platform: Platform = { async call<T>(method: string, args = {}) { const a = args as Record<string, string>; if (method === 'legacy') return {} as T; if (method === 'storage.get') return (values.get(a.key) || null) as T; if (method === 'storage.set') { if (fail) throw new Error('disk full'); values.set(a.key, a.value); } return null as T; }, onEvent() { return () => {}; }, dispose() {} };
  return { platform, values };
}
describe('durable state', () => {
  it('imports profiles and isolates libraries without credentials in application data', () => {
    const data = importLegacy({ profiles: [{ id: 'a', name: 'Alice', locked: true }, { id: 'b', name: 'Bob' }], 'library:a': { favorites: [{ id: '1', name: 'A', path: '/watch/movie/1/a' }], viewing: [] }, skip: { customTypes: '[]' } });
    expect(data.libraries.a.favorites).toHaveLength(1); expect(data.libraries.b.favorites).toEqual([]); expect(data.profiles[0].locked).toBe(true); expect(JSON.stringify(data)).not.toContain('"pin"');
  });
  it('does not mark migration complete on failed persistence', async () => { const { platform, values } = storage(true); await expect(new Repository(platform).initialize()).rejects.toThrow('disk full'); expect(values.has('app')).toBe(false); });
  it('serializes concurrent writes and survives reopen', async () => {
    const { platform } = storage(); const repo = new Repository(platform); await repo.initialize();
    await Promise.all([repo.saveProfile(undefined, 'Alice', 1), repo.saveProfile(undefined, 'Bob', 2)]);
    const reopened = new Repository(platform); await reopened.initialize(); expect(reopened.current.profiles.map(p => p.name)).toEqual(['My profile', 'Alice', 'Bob']);
  });
  it('bounds and isolates progress by profile and anime version', async () => {
    const { platform } = storage(); const repo = new Repository(platform); await repo.initialize(); await repo.saveProfile(undefined, 'Second', 1);
    const title = { id: '1', name: 'Anime', path: '/anime-1', source: 'HIANIME' as const, type: 'SERIES' as const };
    await repo.progress('default', { title, position: 2000, duration: 10000, version: 'DUB' }); await repo.progress('default', { title, position: 3000, duration: 10000, version: 'SUB' });
    expect(repo.current.libraries.default.viewing).toHaveLength(2); expect(repo.current.libraries[repo.current.profiles[1].id].viewing).toEqual([]);
  });
  it('local region type overrides community region', () => { const a: Segment = { id: 'a', type: 'intro', typeId: 'intro', start: 0, end: 10 }; expect(mergeSegments([a], [{ ...a, id: 'b', start: 1 }])).toEqual([{ ...a, id: 'b', start: 1 }]); });
});
describe('playback time', () => {
  it('requires both attendance thresholds and latches confirmation until consent', () => {
    let now = 0; const session = new WatchingSession(() => now, 90, 3, 60);
    for (let i = 0; i < 3; i++) { const token = session.start(i > 0); session.playback(token, true, true); now += 30; session.complete(token, true); expect(session.requireConfirmation(token)).toBe(i === 2); }
    session.interact(); expect(session.pending).toBe(true); now += 60; expect(session.seconds).toBe(0);
  });
  it('excludes paused/background time and retired players', () => {
    let now = 0; const session = new WatchingSession(() => now, 100, 1); const retired = session.start(); session.playback(retired, true, true); now = 10; session.playback(retired, false, false); now = 1000;
    const active = session.start(); session.playback(retired, true, true); session.complete(active, true); expect(session.requireConfirmation(active)).toBe(false);
  });
  it('suspends countdown behind menus and fires once', () => { const c = new Countdown(); expect(c.tick(0, true, true)).toBe(false); expect(c.tick(3000, true, false)).toBe(false); expect(c.tick(10_000, true, true)).toBe(false); expect(c.tick(15_000, true, true)).toBe(true); expect(c.tick(20_000, true, true)).toBe(false); });
  it('accelerates remote seeking only after a held repeat', () => { expect(timelineStep(1000, 5000, 0, 1_000_000)).toBe(1000); expect(timelineStep(1000, 5000, 1, 1_000_000)).toBe(50_000); });
});
