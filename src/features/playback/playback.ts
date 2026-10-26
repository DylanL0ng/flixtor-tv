import { createStore, unwrap } from 'solid-js/store';
import { Countdown, WatchingSession } from './timing';
import { episodeKey, failureOf, finished, mergeSegments, orderedEpisodes, playbackKey, titleKey, type Account, type AnimeVersion, type Detail, type Episode, type Failure, type Segment, type Stream } from '../../domain/models';
import type { Platform, PlayerEvent } from '../../platform/platform';
import type { Repository } from '../../domain/repository';
import type { FlixtorService } from '../../services/flixtor';
import type { AnimeService } from '../../services/anime';
import type { SegmentService } from '../../services/segments';

export function createPlayback(platform: Platform, repo: Repository, flixtor: FlixtorService, anime: AnimeService, segments: SegmentService, profileId: () => string, leave: (destination?: string) => void, accountChanged: (account: Account) => void = () => {}) {
  const [state, set] = createStore<{
    detail?: Detail; episode?: Episode | null; version?: AnimeVersion; id: string; loading: boolean; error?: Failure;
    event?: PlayerEvent; controls: boolean; menu: string; preview?: number; regions: Segment[]; segmentsUnavailable: boolean;
    chooser: boolean; available: AnimeVersion[]; stillWatching?: number; countdown?: number; bufferAhead: boolean; bufferReady: boolean; bufferStalled: boolean;
  }>({ id: '', loading: false, controls: true, menu: '', regions: [], segmentsUnavailable: false, chooser: false, available: [], bufferAhead: false, bufferReady: false, bufferStalled: false });
  let request = 0, abort = new AbortController(), source: Stream | undefined, identityProfile = '', savedAt = 0, interactionAt = performance.now(), foreground = true;
  let recoveryUsed = false, resumeAllowed = true, countdown = new Countdown(), skipped = new Set<string>(), bufferResume = false, bufferGrowth = 0, bufferAt = 0, segmentDuration = 0;
  const attendance = new WatchingSession(() => performance.now());
  const profile = () => repo.current.profiles.find(p => p.id === identityProfile);
  const current = (token: number, signal: AbortSignal) => token === request && !signal.aborted && identityProfile === profileId();
  function command(action: string, args: Record<string, unknown> = {}) { return platform.call('player.command', { id: state.id, action, ...args }); }
  function save() {
    const e = state.event, d = state.detail; if (!e || !d || e.id !== state.id) return Promise.resolve();
    return repo.progress(identityProfile, { title: unwrap(d.title), episode: unwrap(state.episode), version: state.version, position: e.ended ? e.duration : e.position, duration: e.duration });
  }
  async function fetchSegments(token: number, duration: number) {
    const d = state.detail; if (!d) return;
    const local = repo.regions(repo.markKey(d.title, state.episode, state.version));
    try {
      let remote: Segment[] = [];
      if (d.title.source === 'HIANIME') {
        const key = titleKey(d.title), malId = Number(repo.current.skip[`mal:${key}`]) || source?.animeMapping?.malId;
        const episode = Number(repo.current.skip[`animeEpisode:${key}:${episodeKey(state.episode)}`]) || source?.animeMapping?.episode || Number(state.episode?.displayNumber);
        if (malId && episode && duration > 0) remote = await segments.anime({ malId, episode }, duration, abort.signal);
      } else {
        const imdb = repo.current.skip[`imdb:${titleKey(d.title)}`] || d.title.imdb;
        if (imdb) remote = await segments.intro(imdb, state.episode, abort.signal);
      }
      if (request === token) set({ regions: mergeSegments(remote, local), segmentsUnavailable: false });
    } catch (error) { if (request === token && !abort.signal.aborted) set({ regions: local, segmentsUnavailable: true }); }
  }
  async function resolve(token: number, position: number, play: boolean, signal: AbortSignal) {
    if (!current(token, signal)) return;
    const detail = state.detail!; const episode = state.episode;
    const stream = detail.title.source === 'HIANIME' ? await anime.playback(episode!, state.version!, signal) : await flixtor.playback(detail.title, episode, signal);
    if (!current(token, signal)) return;
    source = stream;
    const p = profile(); const id = `${attendance.token}:${token}`; set('id', id);
    await platform.call('player.load', { ...source, id, client: detail.title.source === 'HIANIME' ? 'anime' : 'flixtor', position,
      play: play && resumeAllowed && foreground && !attendance.pending && !attendance.finished,
      quality: p?.quality ?? 0, largerBuffer: p?.largerBuffer ?? false,
      checkpoint: { profileId: identityProfile, title: detail.title, episode: episode ?? null, version: state.version ?? null } }, signal);
    if (current(token, signal)) {
      set('loading', false); void fetchSegments(token, 0);
      // Native errors can arrive before the load acknowledgement.
      if (state.event?.id === id && state.event.error) await recover(state.event);
    }
  }
  async function refresh(token: number, position: number, play: boolean, signal: AbortSignal) {
    recoveryUsed = true;
    const account = await flixtor.account(signal);
    if (!current(token, signal)) return;
    accountChanged(account);
    await resolve(token, position, play, signal);
  }
  async function start(detail: Detail, episode?: Episode | null, version?: AnimeVersion, resume = true, automatic = false) {
    const previousId = state.id, progress = save();
    const stopped = previousId ? platform.call('player.command', { id: previousId, action: 'stop' }) : Promise.resolve();
    abort.abort(); abort = new AbortController(); const token = ++request, signal = abort.signal;
    identityProfile = profileId(); source = undefined; segmentDuration = 0; savedAt = 0; skipped = new Set(); countdown = new Countdown(); recoveryUsed = false;
    attendance.start(automatic); resumeAllowed = foreground; interactionAt = performance.now();
    set({ detail, episode, version, id: '', loading: true, error: undefined, event: undefined, controls: true, menu: '', regions: [], segmentsUnavailable: false,
      chooser: false, available: [], preview: undefined, countdown: undefined, stillWatching: undefined, bufferAhead: false, bufferReady: false, bufferStalled: false });
    try {
      await Promise.all([progress, stopped]);
      if (!current(token, signal)) return;
      const viewing = repo.current.libraries[identityProfile]?.viewing.find(v => playbackKey(v.title, v.episode, v.version) === playbackKey(detail.title, episode, version));
      const position = resume && viewing && !finished(viewing) ? viewing.position : 0;
      if (detail.title.source === 'HIANIME') {
        const choices = await anime.servers(episode!, signal); if (!current(token, signal)) return;
        const available = Object.keys(choices) as AnimeVersion[]; set('available', available);
        if (!version || !choices[version]) { set({ chooser: true, loading: false }); return; }
      }
      try { await resolve(token, position, true, signal); }
      catch (error) {
        if (!current(token, signal) || detail.title.source === 'HIANIME') throw error;
        await refresh(token, position, true, signal);
      }
    } catch (error) { if (current(token, signal)) set({ loading: false, error: failureOf(error) }); }
  }
  async function recover(event: PlayerEvent) {
    if (state.loading || state.error) return;
    if (state.detail?.title.source === 'HIANIME' || recoveryUsed) { set({ error: 'MEDIA', controls: true }); return; }
    const token = ++request, signal = abort.signal, progress = save(), id = state.id;
    const stopped = platform.call('player.command', { id, action: 'stop' });
    recoveryUsed = true; attendance.playback(attendance.token, false, false);
    set({ id: '', loading: true, controls: true });
    try {
      await Promise.all([progress, stopped]);
      if (!current(token, signal)) return;
      await refresh(token, event.position, event.playRequested, signal);
    } catch (error) { if (current(token, signal)) set({ loading: false, error: failureOf(error) }); }
  }
  const next = () => { const list = orderedEpisodes(state.detail?.episodes || []); return list[list.findIndex(e => episodeKey(e) === episodeKey(state.episode)) + 1]; };
  async function advance(automatic: boolean) {
    const e = next(), d = state.detail; if (!e || !d || state.loading || state.error || state.chooser) return;
    const token = request, signal = abort.signal;
    const event = state.event; if (event) await repo.progress(identityProfile, { title: unwrap(d.title), episode: unwrap(state.episode), version: state.version, position: event.duration, duration: event.duration });
    if (!current(token, signal)) return;
    await start(d, e, state.version, true, automatic);
  }
  function effectiveAction(segment: Segment) {
    const p = identityProfile, key = titleKey(state.detail!.title);
    return segment.action || repo.current.skip[`action:${p}:${key}:${segment.typeId}`] || repo.current.skip[`action:${p}::${segment.typeId}`] || 'ASK';
  }
  const unsubscribe = platform.onEvent(event => {
    if (event.type === 'lifecycle') {
      foreground = (event.value as { foreground: boolean }).foreground;
      attendance.playback(attendance.token, state.event?.playing ?? false, foreground);
      if (!foreground) { resumeAllowed = false; void save(); set({ menu: '', bufferAhead: false }); } else set('controls', true);
    }
    if (event.type !== 'player') return;
    const e = event.value as PlayerEvent; if (!state.id || e.id !== state.id || identityProfile !== profileId()) return;
    set('event', e); attendance.playback(attendance.token, e.playing, foreground);
    if (e.error) { void recover(e); return; }
    if (e.duration > 0 && segmentDuration !== e.duration) { segmentDuration = e.duration; void fetchSegments(request, e.duration); }
    if (e.time - savedAt >= 5000 && e.playing) { savedAt = e.time; void save(); }
    if (state.bufferAhead) {
      if (e.buffered > bufferGrowth) { bufferGrowth = e.buffered; bufferAt = performance.now(); }
      set({ bufferReady: e.duration > 0 && (e.buffered - e.position >= Math.min(90_000, e.duration - e.position) || e.bytes >= e.byteLimit), bufferStalled: performance.now() - bufferAt > 15_000 });
    }
    if (e.ended) { attendance.complete(attendance.token, !!state.episode); if (profile()?.autoplay && next()) attendance.requireConfirmation(attendance.token); }
  });
  const timer = setInterval(() => {
    const e = state.event; if (!e || e.id !== state.id || state.error || state.loading) return;
    const seconds = attendance.seconds; set('stillWatching', seconds);
    if (seconds === 0) { void stop(); return; }
    const active = state.regions.filter(r => r.start <= e.position && e.position < r.end && r.end <= e.duration).at(-1);
    const canRun = foreground && !state.menu && !state.bufferAhead && !state.chooser && !attendance.pending && state.preview === undefined && !e.buffering;
    if (active && effectiveAction(active) === 'AUTO' && !skipped.has(active.id) && canRun && e.playing) { skipped.add(active.id); void command('seek', { position: active.end }); }
    const credits = state.regions.find(r => r.type === 'outro' && r.end <= e.duration && e.position >= r.start && (e.position < r.end || skipped.has(r.id)));
    const eligible = !!next() && !!profile()?.autoplay && (e.ended || !!credits && !attendance.dueAtCredits(attendance.token));
    if (countdown.tick(performance.now(), eligible, canRun && (e.ended || e.playing))) void advance(true);
    set('countdown', eligible && !countdown.cancelled ? Math.ceil(countdown.remaining / 1000) : undefined);
    if (e.playing && canRun && !e.ended && performance.now() - interactionAt > 6000) set('controls', false);
  }, 250);
  async function cancel() {
    const id = state.id, progress = save();
    abort.abort(); request++; attendance.reset();
    set({ id: '', event: undefined, loading: false, error: undefined, menu: '', stillWatching: undefined });
    await Promise.all([progress, id ? platform.call('player.command', { id, action: 'stop' }) : Promise.resolve()]);
  }
  async function stop(destination?: string) { const work = cancel(), token = request; await work; if (token === request) leave(destination); }
  return {
    state, start, stop, cancel, command, next, save, effectiveAction,
    interact() { attendance.interact(); interactionAt = performance.now(); set('controls', true); },
    menu(value: string) { interactionAt = performance.now(); set({ menu: value, controls: true }); void command('captions', { bottom: value ? 0.32 : 0.2, right: value ? 0.38 : 0.05 }); },
    preview(position?: number) { set('preview', position === undefined ? undefined : Math.max(0, Math.min(state.event?.duration || 0, position))); },
    async commitSeek() { if (state.preview !== undefined) await command('seek', { position: state.preview }); set('preview', undefined); },
    skip(segment: Segment) { skipped.add(segment.id); return command('seek', { position: segment.end }); },
    cancelNext() { countdown.cancel(); set('countdown', undefined); },
    choose(version: AnimeVersion) { return start(state.detail!, state.episode, version); },
    continueWatching() { attendance.reset(); return advance(true); },
    nextEpisode() { attendance.interact(); return advance(false); },
    async buffer(open: boolean, play = false) {
      if (open) { bufferResume = state.event?.playing ?? false; bufferGrowth = state.event?.buffered || 0; bufferAt = performance.now(); await command('pause'); await command('buffer', { enabled: true }); }
      else { await command('buffer', { enabled: profile()?.largerBuffer ?? false }); if (play && bufferResume && foreground) await command('play'); }
      set({ bufferAhead: open, bufferReady: false, bufferStalled: false });
    },
    refreshRegions() { return fetchSegments(request, state.event?.duration || 0); },
    dispose() { clearInterval(timer); unsubscribe(); abort.abort(); request++; }
  };
}
