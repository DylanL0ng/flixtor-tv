import { createContext, createSignal, onCleanup, useContext } from 'solid-js';
import { createStore } from 'solid-js/store';
import { QueryClient } from '@tanstack/solid-query';
import type { HostInfo, Platform } from '../platform/platform';
import type { Repository } from '../domain/repository';
import { FlixtorService } from '../services/flixtor';
import { AnimeService } from '../services/anime';
import { SegmentService } from '../services/segments';
import { createUpdates } from '../features/updates/updates';
import type { Title, Detail, Episode, AnimeVersion } from '../domain/models';
import type { createPlayback } from '../features/playback/playback';

export function createApp(platform: Platform, host: HostInfo, repo: Repository) {
  const [data, setData] = createStore(repo.current);
  const [profileId, setProfileId] = createSignal('');
  const [notice, setNotice] = createSignal('');
  const [selected, setSelected] = createSignal<Title>();
  const [authRevision, setAuthRevision] = createSignal(0);
  const queries = new QueryClient({ defaultOptions: { queries: { staleTime: 60_000, gcTime: 5 * 60_000, retry: 1, refetchOnWindowFocus: false } } });
  const unsubscribe = repo.subscribe(value => setData(value));
  const flixtor = new FlixtorService(platform), anime = new AnimeService(platform), segments = new SegmentService(platform);
  const updates = createUpdates(platform, host);
  let noticeTimer: ReturnType<typeof setTimeout> | undefined;
  function notify(value: string) { clearTimeout(noticeTimer); setNotice(value); noticeTimer = setTimeout(() => setNotice(''), 6000); }
  async function run(action: () => Promise<unknown>) { try { await action(); } catch (e) { notify(e instanceof Error ? e.message : 'Action failed. Try again.'); } }
  onCleanup(() => { unsubscribe(); queries.clear(); updates.dispose(); clearTimeout(noticeTimer); platform.dispose(); });
  return { platform, host, repo, data, profileId, setProfileId, navigate: undefined as ((path: string) => void) | undefined,
    backHandlers: new Map<symbol, () => boolean>(), pendingPlayback: undefined as { detail: Detail; episode?: Episode | null; version?: AnimeVersion } | undefined,
    resumePending: async () => {}, titleCache: new Map<string, Title>(), lastFocus: '', playback: undefined as ReturnType<typeof createPlayback> | undefined, profile: () => data.profiles.find(p => p.id === profileId()),
    notice, notify, run, selected, setSelected, authRevision, refreshAccount: () => { setAuthRevision(v => v + 1); void queries.invalidateQueries({ queryKey: ['account'] }); },
    queries, flixtor, anime, segments, updates,
    input: (label: string, value = '', secret = false) => platform.call<string | null>('input', { label, value, secret }) };
}
export type App = ReturnType<typeof createApp>;
export const AppContext = createContext<App>();
export function useApp() { const app = useContext(AppContext); if (!app) throw new Error('App context unavailable'); return app; }
