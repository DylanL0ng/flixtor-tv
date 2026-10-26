import { createEffect, createSignal, For, on, onCleanup, Show } from 'solid-js';
import { Column, Row } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Glass, Menu, motion, palette, time, restoreFocus, type Option } from '../../ui/components';
import { Overlay } from '../../ui/Overlay';
import { Timeline } from '../../ui/Timeline';
import { EpisodeList } from '../../ui/Episodes';
import { RegionEditor } from '../../ui/RegionEditor';
import { narratePage } from '../../ui/narration';
import { episodeKey, orderedEpisodes, titleKey, type Segment, type SegmentType, type SkipAction } from '../../domain/models';
import { failureMessages } from '../../ui/copy';
export function PlayerPage() {
  const app = useApp(), player = app.playback!, state = player.state;
  narratePage(() => state.detail?.title.name || 'Playback', () => state.episode?.name || '');
  const [draft, setDraft] = createSignal<Segment>();
  const [regionPreview, setRegionPreview] = createSignal<number>();
  const [episodeSeason, setEpisodeSeason] = createSignal<number>();
  createEffect(on(() => state.menu, (menu, previous) => { if (menu === 'episodes' && previous !== 'seasons') setEpisodeSeason(state.episode?.season); }));
  let editorOrigin = 0, editorResume = false;
  const run = app.run, e = () => state.event;
  const profile = () => app.profile()!;
  const key = () => app.repo.markKey(state.detail!.title, state.episode, state.version);
  async function enterBoundary(boundary: 'start' | 'end') {
    const original = draft(); if (!original) return;
    const value = await app.requestInput({ label: `${boundary === 'start' ? 'Start' : 'End'} time in seconds`, value: String(original[boundary] / 1000), mode: 'time', validate: text => {
      const ms = Number(text) * 1000;
      return !text.trim() || !Number.isFinite(ms) || ms < 0 || ms > (e()?.duration || 0) || (boundary === 'start' ? ms >= original.end : ms <= original.start) ? 'End must follow start and remain inside video.' : undefined;
    } });
    if (value === null || draft()?.id !== original.id) return;
    const position = Math.round(Number(value) * 1000); setDraft(current => ({ ...current!, [boundary]: position })); setRegionPreview(position); await player.command('seek', { position });
  }
  function closeEditor() {
    setRegionPreview(undefined);
    void player.command('seek', { position: editorOrigin });
    if (editorResume) void player.command('play'); editorResume = false; setDraft(undefined); player.menu('skip');
  }
  function edit(region?: Segment) {
    setRegionPreview(undefined);
    editorOrigin = e()?.position || 0; editorResume = e()?.playing || false; void player.command('pause');
    setDraft(region ? { ...region } : { id: crypto.randomUUID(), type: 'intro', typeId: 'intro', start: Math.floor(editorOrigin), end: Math.min(e()?.duration || Infinity, Math.floor(editorOrigin) + 10_000), source: 'LOCAL' });
    player.menu('editor');
  }
  const segmentTypes: { id: string; label: string; type: SegmentType }[] = [
    { id: 'intro', label: 'Intro', type: 'intro' }, { id: 'recap', label: 'Recap', type: 'recap' }, { id: 'outro', label: 'Credits', type: 'outro' }, { id: 'post_credits', label: 'Post-credits', type: 'post_credits' },
    ...app.repo.customTypes().map(t => ({ ...t, type: 'custom' as const }))
  ];
  const options = (): Option[] => {
    const menu = state.menu;
    if (menu === 'settings') return [
      { id: 'quality', label: 'Video quality', value: profile().quality ? `${profile().quality}p maximum` : 'Auto', action: () => player.menu('quality') },
      { id: 'audio', label: 'Audio', action: () => player.menu('audio') },
      { id: 'subtitles', label: 'Subtitles', action: () => player.menu('subtitles') },
      { id: 'autoplay', label: 'Autoplay', value: profile().autoplay ? 'On' : 'Off', action: () => run(() => app.repo.updateProfile(profile().id, { autoplay: !profile().autoplay })) },
      { id: 'buffer', label: 'Larger buffer', value: profile().largerBuffer ? 'On' : 'Off', action: () => run(async () => { const enabled = !profile().largerBuffer; await app.repo.updateProfile(profile().id, { largerBuffer: enabled }); await player.command('buffer', { enabled }); }) },
      { id: 'skip', label: 'Skip segments', action: () => player.menu('skip') },
      { id: 'buffer-ahead', label: 'Buffer ahead', action: () => run(async () => { player.menu(''); await player.buffer(true); }) },
      { id: 'close', label: 'Close settings', action: () => player.menu('') },
    ];
    if (menu === 'quality') return [0, 720, 1080, 2160].map(height => ({ id: String(height), label: height ? `${height}p maximum` : 'Auto', selected: profile().quality === height, action: () => run(async () => { await app.repo.updateProfile(profile().id, { quality: height }); await player.command('quality', { height }); player.menu('settings'); }) }));
    if (menu === 'audio' || menu === 'subtitles') {
      const type = menu === 'audio' ? 1 : 3;
      return [{ id: 'auto', label: type === 1 ? 'Auto' : 'Off', action: () => run(async () => { await player.command('track', { type, track: type === 1 ? 'auto' : 'off' }); player.menu('settings'); }) },
        ...(e()?.tracks.filter(t => t.type === type).map(t => ({ id: t.id, label: t.label, disabled: !t.supported, selected: t.selected, action: () => run(async () => { await player.command('track', { type, track: t.id }); player.menu('settings'); }) })) || [])];
    }
    if (menu === 'episodes') return orderedEpisodes(state.detail?.episodes || []).map(episode => ({ id: episodeKey(episode), label: `${episode.season}.${episode.displayNumber || episode.number}  ${episode.name}`, selected: episodeKey(episode) === episodeKey(state.episode), action: () => run(() => player.start(state.detail!, episode)) }));
    if (menu === 'seasons') return [...new Set(orderedEpisodes(state.detail?.episodes || []).map(episode => episode.season))].map(season => ({ id: String(season), label: `Season ${season}`, selected: season === episodeSeason(), action: () => { setEpisodeSeason(season); player.menu('episodes'); } }));
    if (menu === 'skip') return [
      { id: 'add', label: 'Mark a new region', action: () => edit() },
      { id: 'types', label: 'Create custom region type', action: () => run(async () => { const label = await app.input('Region type name'); if (label !== null) await app.repo.customType(label); }) },
      { id: 'mapping', label: state.detail?.title.source === 'HIANIME' ? 'MAL / episode mapping' : 'IMDb mapping', action: () => run(async () => {
        const title = state.detail!.title, tkey = titleKey(title);
        if (title.source === 'HIANIME') {
          const mal = await app.input('MAL ID (blank clears override)', app.data.skip[`mal:${tkey}`] || ''); if (mal === null) return;
          if (mal && !/^\d+$/.test(mal) || mal && Number(mal) <= 0) throw new Error('Enter a positive MAL ID.');
          const number = await app.input('Episode number', app.data.skip[`animeEpisode:${tkey}:${episodeKey(state.episode)}`] || state.episode?.displayNumber || ''); if (number === null) return;
          if (number && (!Number.isFinite(Number(number)) || Number(number) <= 0)) throw new Error('Enter a positive episode number.');
          await app.repo.setSkip(`mal:${tkey}`, mal || undefined); await app.repo.setSkip(`animeEpisode:${tkey}:${episodeKey(state.episode)}`, number || undefined);
        } else { const imdb = await app.input('IMDb ID (tt followed by 7–8 digits)', app.data.skip[`imdb:${tkey}`] || title.imdb || ''); if (imdb === null) return; if (imdb && !/^tt\d{7,8}$/.test(imdb)) throw new Error('Enter a valid IMDb ID.'); await app.repo.setSkip(`imdb:${tkey}`, imdb || undefined); }
        await player.refreshRegions();
      }) },
      { id: 'refresh', label: state.segmentsUnavailable ? 'Timestamps unavailable — retry' : 'Refresh timestamps', action: () => run(player.refreshRegions) },
      ...state.regions.map(region => ({ id: region.id, label: `${region.typeId}  ${time(region.start)}–${time(region.end)}`, value: player.effectiveAction(region), action: () => edit(region) })),
      { id: 'preferences', label: 'Skip actions for this show', action: () => player.menu('skip-actions') },
      { id: 'back', label: 'Back to settings', action: () => player.menu('settings') },
    ];
    if (menu === 'skip-actions') return segmentTypes.map(type => ({ id: type.id, label: type.label, value: app.data.skip[`action:${profile().id}:${titleKey(state.detail!.title)}:${type.id}`] || 'Inherit profile', action: () => run(async () => {
      const key = `action:${profile().id}:${titleKey(state.detail!.title)}:${type.id}`;
      const values = [undefined, 'ASK', 'AUTO', 'OFF']; await app.repo.setSkip(key, values[(values.indexOf(app.data.skip[key]) + 1) % values.length]);
    }) }));
    if (menu === 'editor' && draft()) return [
      { id: 'type', label: 'Region type', value: draft()!.typeId, action: () => player.menu('editor-type') },
      { id: 'start', label: 'Start time', value: time(draft()!.start), action: () => run(() => enterBoundary('start')) },
      { id: 'end', label: 'End time', value: time(draft()!.end), action: () => run(() => enterBoundary('end')) },
      { id: 'action', label: 'Action', value: draft()!.action || 'Inherit', action: () => setDraft(d => ({ ...d!, action: [undefined, 'ASK', 'AUTO', 'OFF'][([undefined, 'ASK', 'AUTO', 'OFF'] as (SkipAction | undefined)[]).indexOf(d!.action) + 1 > 3 ? 0 : ([undefined, 'ASK', 'AUTO', 'OFF'] as (SkipAction | undefined)[]).indexOf(d!.action) + 1] as SkipAction | undefined })) },
      { id: 'save', label: 'Save region', disabled: regionPreview() !== undefined && Math.abs((e()?.position || 0) - regionPreview()!) > 1000, action: () => run(async () => { await app.repo.saveRegion(key(), { ...draft()!, source: 'LOCAL' }, e()?.duration || 0); closeEditor(); await player.refreshRegions(); }) },
      { id: 'delete', label: 'Delete local region', disabled: !app.repo.regions(key()).some(s => s.id === draft()!.id), action: () => run(async () => { await app.repo.deleteRegion(key(), draft()!.id); closeEditor(); await player.refreshRegions(); }) },
      { id: 'contribute', label: 'Contribute timestamps', disabled: !app.host.contribution || state.detail?.title.source === 'HIANIME' || !['intro', 'recap', 'outro', 'post_credits'].includes(draft()!.type), action: () => run(async () => { const imdb = app.data.skip[`imdb:${titleKey(state.detail!.title)}`] || state.detail?.title.imdb; if (!imdb) throw new Error('Set an IMDb mapping first.'); await app.segments.submit(draft()!, imdb, state.episode); app.notify('Contribution sent.'); }) },
      { id: 'cancel', label: 'Cancel editing', action: closeEditor },
    ];
    if (menu === 'editor-type') return segmentTypes.map(t => ({ id: t.id, label: t.label, selected: draft()?.typeId === t.id, action: () => { setDraft(d => ({ ...d!, type: t.type, typeId: t.id })); player.menu('editor'); } }));
    return [];
  };
  const activeSegment = () => state.regions.filter(s => s.start <= (e()?.position || 0) && (e()?.position || 0) < s.end && s.end <= (e()?.duration || 0) && player.effectiveAction(s) === 'ASK').at(-1);
  const back = () => { if (draft()) closeEditor(); else player.menu(state.menu === 'settings' || state.menu === 'episodes' ? '' : state.menu === 'seasons' ? 'episodes' : state.menu === 'skip-actions' ? 'skip' : 'settings'); };
  const backKey = Symbol('player'); app.backHandlers.set(backKey, () => { if (draft()) { closeEditor(); return true; } return false; });
  onCleanup(() => { app.backHandlers.delete(backKey); if (draft()) editorResume = false; void player.cancel().catch(() => {}); });
  createEffect(() => {
    if (!state.menu && state.controls && state.event) queueMicrotask(() => restoreFocus('player-settings'));
  });
  const safeX = () => 1920 * app.data.fit.horizontal / 100, safeY = () => 1080 * app.data.fit.vertical / 100;
  const safeWidth = () => 1920 - 2 * safeX();
  createEffect(() => {
    if (state.id) void player.command('captions', { bottom: (state.controls || state.menu ? 320 + safeY() : safeY()) / 1080, right: state.menu ? 0.43 : app.data.fit.horizontal / 100 });
  });
  return <view width={1920} height={1080} onAny={() => player.interact()}>
    <Show when={state.loading}><view x={safeX()} y={safeY() + 70} width={safeWidth()} height={110}><Glass width={650} height={100} solid /><text skipFocus x={30} y={28} width={590} height={60} fontSize={32}>Preparing playback…</text></view></Show>
    <Show when={state.error}><Overlay title="Playback unavailable" close={() => run(player.stop)} width={1120} height={820}>
      <Menu width={1040} height={748} title={failureMessages[state.error!]} options={[
        { id: 'retry', label: 'Try again', action: () => run(() => player.start(state.detail!, state.episode, state.version)) },
        { id: 'account', label: 'Check account in Settings', action: () => run(async () => { app.pendingPlayback = { detail: state.detail!, episode: state.episode, version: state.version }; await player.stop('/settings/account'); }) },
        { id: 'updates', label: 'Check for update', action: () => run(() => app.updates.check('player.response-v1')) },
        { id: 'report', label: 'Notify Dylan', disabled: state.error !== 'CHANGED' || !app.host.reportUrl || ['sending', 'sent'].includes(app.updates.state.report), action: () => run(() => app.updates.report('PLAYER')) },
        { id: 'back', label: 'Back', action: () => run(player.stop) },
      ]} />
    </Overlay></Show>
    <Show when={state.chooser}><Overlay title="Choose anime version" close={() => run(player.stop)} width={980} height={550}>
      <Menu width={900} height={478} title="Choose anime version" options={[
        { id: 'dub', label: 'English dub', disabled: !state.available.includes('DUB'), action: () => run(() => player.choose('DUB')) },
        { id: 'sub', label: 'Japanese with subtitles', disabled: !state.available.includes('SUB'), action: () => run(() => player.choose('SUB')) },
        { id: 'back', label: 'Cancel', action: () => run(player.stop) },
      ]} />
    </Overlay></Show>
    <Show when={state.stillWatching !== undefined}><Overlay title="Are you still watching?" close={() => run(player.stop)} width={960} height={520}>
      <Menu width={880} height={448} title={`Still watching? ${state.stillWatching}s`} options={[
        { id: 'continue', label: 'Continue watching', action: () => run(player.continueWatching) },
        { id: 'stop', label: 'Stop watching', action: () => run(player.stop) },
      ]} />
    </Overlay></Show>
    <Show when={state.bufferAhead}><Overlay title="Buffer ahead" close={() => run(() => player.buffer(false))} width={960} height={570}>
      <Menu width={880} height={498} title={state.bufferReady ? 'Buffer ready' : state.bufferStalled ? 'Buffer stalled — connection may be slow' : `Buffering ahead… ${Math.max(0, Math.round(((e()?.buffered || 0) - (e()?.position || 0)) / 1000))}s`} options={[
        { id: 'play', label: 'Play now', action: () => run(() => player.buffer(false, true)) },
        { id: 'cancel', label: 'Cancel', action: () => run(() => player.buffer(false)) },
      ]} />
    </Overlay></Show>
    <Show when={e() && !state.error && !state.chooser && state.stillWatching === undefined && !state.bufferAhead}>
      <Show when={state.controls || state.menu}>
        <view skipFocus y={690} width={1920} height={390} color={0x00000000} linearGradient={{ colors: [0x00000000, 0x080c14ee], stops: [0, 1], angle: 0 }} />
        <Column x={safeX()} y={1080 - safeY() - 292} width={safeWidth()} height={292} gap={16} scroll="none">
          <text skipFocus width={safeWidth()} height={48} fontSize={32} fontFamily="Roboto700" maxLines={1}>{state.detail?.title.name || ''}{state.episode ? `   ·   ${state.episode.name}` : ''}</text>
          <Timeline width={safeWidth()} />
          <view width={safeWidth()} height={100}><Glass width={Math.min(safeWidth(), 960)} height={96} radius={48} />
            <Row x={16} y={14} width={Math.min(safeWidth(), 960) - 32} height={68} gap={18}>
              <Button label={e()?.playing ? 'Pause' : 'Play'} icon={e()?.playing ? 'pause' : 'play'} iconOnly primary width={76} action={() => run(() => player.command(e()?.playing ? 'pause' : 'play'))} />
              <Button label="−10 seconds" icon="rewind" iconOnly width={76} quiet action={() => run(() => player.command('seek', { position: Math.max(0, e()!.position - 10_000) }))} />
              <Button label="+10 seconds" icon="forward" iconOnly width={76} quiet action={() => run(() => player.command('seek', { position: Math.min(e()!.duration, e()!.position + 10_000) }))} />
              <Show when={state.detail?.episodes.length}><Button label="Episodes" id="player-episodes" icon="episodes" width={210} quiet action={() => player.menu('episodes')} /></Show>
              <Button label="Settings" id="player-settings" icon="settings" width={210} quiet action={() => player.menu('settings')} />
              <Show when={player.next()}><Button label="Next episode" icon="forward" iconOnly width={76} quiet action={() => run(player.nextEpisode)} /></Show>
            </Row>
          </view>
        </Column>
      </Show>
      <Show when={!state.menu && !draft() && state.preview === undefined}>
        <view x={Math.max(safeX(), 1920 - safeX() - 590)} y={1080 - safeY() - (state.controls ? 430 : 160)} width={590} height={120}>
          <Show when={state.undo}><Button label={`Skipped ${state.undo!.type} · Undo`} width={470} action={() => run(player.undoSkip)} /></Show>
          <Show when={!state.undo && activeSegment() && state.countdown === undefined}><Button label={`Skip ${activeSegment()!.typeId}`} width={320} action={() => run(() => player.skip(activeSegment()!))} /></Show>
          <Show when={!state.undo && state.countdown !== undefined}><Row width={590} height={80} gap={18}><Button label={`Next in ${state.countdown}s`} icon="forward" width={330} primary action={() => run(player.nextEpisode)} /><Button label="Cancel" width={210} action={player.cancelNext} /></Row></Show>
        </view>
      </Show>
      <Show when={state.menu}><Overlay title={state.menu === 'editor' ? 'Edit region' : state.menu === 'episodes' ? 'Episodes' : 'Playback settings'} width={state.menu === 'editor' ? 1480 : 720} drawer close={back}>
        <Show when={state.menu === 'episodes'} fallback={
          <Show when={state.menu} keyed>{menu => <Show when={menu === 'editor' && draft()} fallback={<Menu title={menu.replaceAll('-', ' ')} width={640} height={828} options={options()} back={back} />}>
            <Row width={1400} height={828} gap={40}>
              <RegionEditor segment={draft()!} duration={e()?.duration || 0} change={setDraft} preview={position => { setRegionPreview(position); void player.command('seek', { position }); }} />
              <Menu title="Region options" width={720} height={828} options={options()} back={back} />
            </Row>
          </Show>}</Show>
        }>
          <Column width={640} height={828} gap={24} scroll="none">
            <Row width={640} height={68} gap={20}><text skipFocus width={300} height={64} fontSize={40} fontFamily="Roboto700">Episodes</text><Button label={`Season ${episodeSeason()}`} width={300} action={() => player.menu('seasons')} /></Row>
            <EpisodeList title={state.detail!.title} episodes={orderedEpisodes(state.detail?.episodes || []).filter(episode => episode.season === episodeSeason())} width={640} height={730} current={state.episode} autofocus
              viewing={app.data.libraries[app.profileId()]?.viewing || []} play={episode => run(() => player.start(state.detail!, episode))} />
          </Column>
        </Show>
      </Overlay></Show>
    </Show>
  </view>;
}
