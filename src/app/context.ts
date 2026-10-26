import { createContext, createSignal, onCleanup, useContext } from 'solid-js';
import { createStore } from 'solid-js/store';
import { QueryClient } from '@tanstack/solid-query';
import type { HostInfo, Platform } from '../platform/platform';
import type { Repository } from '../domain/repository';
import { FlixtorService } from '../services/flixtor';
import { AnimeService } from '../services/anime';
import { SegmentService } from '../services/segments';
import { createUpdates } from '../features/updates/updates';
import type { Title, Detail, Episode, AnimeVersion, StageArtwork } from '../domain/models';
import { CinemetaService } from '../services/cinemeta';
import type { createPlayback } from '../features/playback/playback';
import type { InputRequest } from '../ui/input-state';
import type { BrowseOrigin } from '../features/catalog/CatalogShelves';

export function createApp(platform: Platform, host: HostInfo, repo: Repository, queries: QueryClient) {
  const [data, setData] = createStore(repo.current);
  const [profileId, setProfileId] = createSignal('');
  const [notice, setNotice] = createSignal('');
  const [selected, setSelected] = createSignal<Title>();
  const [stage, setStage] = createSignal<StageArtwork>();
  const [authRevision, setAuthRevision] = createSignal(0);
  const [inputRequest, setInputRequest] = createSignal<InputRequest>();
  const unsubscribe = repo.subscribe(value => setData(value));
  const flixtor = new FlixtorService(platform), anime = new AnimeService(platform), segments = new SegmentService(platform), cinemeta = new CinemetaService(platform);
  const updates = createUpdates(platform, host);
  let noticeTimer: ReturnType<typeof setTimeout> | undefined;
  function notify(value: string) { clearTimeout(noticeTimer); setNotice(value); noticeTimer = setTimeout(() => setNotice(''), 6000); }
  async function run(action: () => Promise<unknown>) { try { await action(); } catch (e) { notify(e instanceof Error ? e.message : 'Action failed. Try again.'); } }
  function requestInput(options: Omit<InputRequest, 'resolve'>) {
    return new Promise<string | null>(resolve => {
      inputRequest()?.resolve(null);
      const request: InputRequest = { ...options, resolve(value) { if (inputRequest() === request) setInputRequest(undefined); resolve(value); } };
      setInputRequest(request);
    });
  }
  onCleanup(() => { inputRequest()?.resolve(null); unsubscribe(); queries.clear(); updates.dispose(); clearTimeout(noticeTimer); platform.dispose(); });
  return { platform, host, repo, data, profileId, setProfileId, navigate: undefined as ((path: string) => void) | undefined,
    backHandlers: new Map<symbol, () => boolean>(), pendingPlayback: undefined as { detail: Detail; episode?: Episode | null; version?: AnimeVersion } | undefined,
    resumePending: async () => {}, titleCache: new Map<string, Title>(), browseOrigins: new Map<string, BrowseOrigin>(), lastFocus: '', lastProfile: '', playback: undefined as ReturnType<typeof createPlayback> | undefined, profile: () => data.profiles.find(p => p.id === profileId()),
    notice, notify, run, selected, setSelected, stage, setStage, authRevision, refreshAccount: () => { setAuthRevision(v => v + 1); void queries.invalidateQueries({ queryKey: ['account'] }); },
    queries, flixtor, anime, segments, cinemeta, updates,
    inputRequest, requestInput,
    input: (label: string, value = '', secret = false) => requestInput({ label, value, mode: secret ? 'password' : /seconds|number|MAL ID/i.test(label) ? 'time' : 'text',
      maxLength: label === 'Profile name' ? 30 : 256,
      validate: value => label === 'Profile name' && !value.trim() ? 'Name is required.' : /seconds/i.test(label) && (!value.trim() || !Number.isFinite(Number(value)) || Number(value) < 0) ? 'Enter a valid time in seconds.' : undefined }) };
}
export type App = ReturnType<typeof createApp>;
export const AppContext = createContext<App>();
export function useApp() { const app = useContext(AppContext); if (!app) throw new Error('App context unavailable'); return app; }
