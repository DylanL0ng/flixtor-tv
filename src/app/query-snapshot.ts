import { dehydrate, hydrate, type DehydratedState, type QueryClient } from '@tanstack/solid-query';
import type { Platform } from '../platform/platform';

const KEY = 'querySnapshot', VERSION = 1, TTL = 7 * 24 * 60 * 60_000, MAX_SIZE = 1_800_000;
type Snapshot = { version: number; savedAt: number; state: DehydratedState };

export async function restoreQuerySnapshot(client: QueryClient, platform: Platform) {
  const raw = await platform.call<string | null>('storage.get', { key: KEY });
  if (!raw) return;
  try {
    const snapshot = JSON.parse(raw) as Snapshot;
    if (snapshot.version !== VERSION || Date.now() - snapshot.savedAt > TTL || !snapshot.state?.queries) throw new Error('Expired snapshot');
    hydrate(client, snapshot.state);
  } catch { await platform.call('storage.remove', { key: KEY }); }
}

export async function saveQuerySnapshot(client: QueryClient, platform: Platform) {
  const state = dehydrate(client, { shouldDehydrateQuery: query => query.state.status === 'success' && ['discovery', 'home', 'detail'].includes(String(query.queryKey[0])) });
  const raw = JSON.stringify({ version: VERSION, savedAt: Date.now(), state } satisfies Snapshot);
  if (raw.length > MAX_SIZE) { await platform.call('storage.remove', { key: KEY }); return; }
  await platform.call('storage.set', { key: KEY, value: raw });
}
