import { QueryClient } from '@tanstack/solid-query';
import { describe, expect, it } from 'vitest';
import { restoreQuerySnapshot, saveQuerySnapshot } from '../src/app/query-snapshot';
import type { Platform } from '../src/platform/platform';

describe('query startup snapshot', () => {
  it('restores discovery data before Home refetches', async () => {
    const storage = new Map<string, string>();
    const platform = { async call(method: string, args: Record<string, string> = {}) {
      if (method === 'storage.get') return storage.get(args.key) || null;
      if (method === 'storage.set') { storage.set(args.key, args.value); return true; }
      if (method === 'storage.remove') { storage.delete(args.key); return true; }
      return null;
    } } as unknown as Platform;
    const source = new QueryClient();
    source.setQueryData(['discovery', 'movie', 'top'], [{ id: 'tt1234567', name: 'Northbound' }]);
    source.setQueryData(['search', 'private'], ['exclude']);
    await saveQuerySnapshot(source, platform);

    const restored = new QueryClient();
    await restoreQuerySnapshot(restored, platform);
    expect(restored.getQueryData(['discovery', 'movie', 'top'])).toEqual([{ id: 'tt1234567', name: 'Northbound' }]);
    expect(restored.getQueryData(['search', 'private'])).toBeUndefined();
    source.clear(); restored.clear();
  });
});
