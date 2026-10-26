import { createSignal, For, onCleanup, Show } from 'solid-js';
import { Column, Row } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Menu, motion, palette, time, type Option } from '../../ui/components';
import { episodeKey, orderedEpisodes, titleKey, type Segment, type SegmentType, type SkipAction } from '../../domain/models';
import { failureMessages } from '../../ui/copy';
export function PlayerPage() {
  const app = useApp(), player = app.playback!, state = player.state;
  const [draft, setDraft] = createSignal<Segment>();
  let editorOrigin = 0, editorResume = false;
  const run = app.run, e = () => state.event;
  const profile = () => app.profile()!;
  const key = () => app.repo.markKey(state.detail!.title, state.episode, state.version);
  function closeEditor() {
    void player.command('seek', { position: editorOrigin });
    if (editorResume) void player.command('play'); editorResume = false; setDraft(undefined); player.menu('skip');
  }
  function edit(region?: Segment) {
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
      { id: 'close', label: 'Close settings', action: () => player.menu('') },
    ];
    if (menu === 'quality') return [0, 720, 1080, 2160].map(height => ({ id: String(height), label: height ? `${height}p maximum` : 'Auto', selected: profile().quality === height, action: () => run(async () => { await app.repo.updateProfile(profile().id, { quality: height }); await player.command('quality', { height }); player.menu('settings'); }) }));
    if (menu === 'audio' || menu === 'subtitles') {
      const type = menu === 'audio' ? 1 : 3;
      return [{ id: 'auto', label: type === 1 ? 'Auto' : 'Off', action: () => run(async () => { await player.command('track', { type, track: type === 1 ? 'auto' : 'off' }); player.menu('settings'); }) },
        ...(e()?.tracks.filter(t => t.type === type).map(t => ({ id: t.id, label: t.label, disabled: !t.supported, selected: t.selected, action: () => run(async () => { await player.command('track', { type, track: t.id }); player.menu('settings'); }) })) || [])];
    }
    if (menu === 'episodes') return orderedEpisodes(state.detail?.episodes || []).map(episode => ({ id: episodeKey(episode), label: `${episode.season}.${episode.displayNumber || episode.number}  ${episode.name}`, selected: episodeKey(episode) === episodeKey(state.episode), action: () => run(() => player.start(state.detail!, episode)) }));
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
      { id: 'start', label: 'Start time', value: time(draft()!.start), action: () => run(async () => { const value = await app.input('Start time in seconds', String(draft()!.start / 1000)); if (value !== null && Number.isFinite(Number(value))) { setDraft(d => ({ ...d!, start: Math.round(Number(value) * 1000) })); await player.command('seek', { position: draft()!.start }); } }) },
      { id: 'end', label: 'End time', value: time(draft()!.end), action: () => run(async () => { const value = await app.input('End time in seconds', String(draft()!.end / 1000)); if (value !== null && Number.isFinite(Number(value))) { setDraft(d => ({ ...d!, end: Math.round(Number(value) * 1000) })); await player.command('seek', { position: draft()!.end }); } }) },
      { id: 'action', label: 'Action', value: draft()!.action || 'Inherit', action: () => setDraft(d => ({ ...d!, action: [undefined, 'ASK', 'AUTO', 'OFF'][([undefined, 'ASK', 'AUTO', 'OFF'] as (SkipAction | undefined)[]).indexOf(d!.action) + 1 > 3 ? 0 : ([undefined, 'ASK', 'AUTO', 'OFF'] as (SkipAction | undefined)[]).indexOf(d!.action) + 1] as SkipAction | undefined })) },
      { id: 'save', label: 'Save region', action: () => run(async () => { await app.repo.saveRegion(key(), { ...draft()!, source: 'LOCAL' }, e()?.duration || 0); closeEditor(); await player.refreshRegions(); }) },
      { id: 'delete', label: 'Delete local region', disabled: !app.repo.regions(key()).some(s => s.id === draft()!.id), action: () => run(async () => { await app.repo.deleteRegion(key(), draft()!.id); closeEditor(); await player.refreshRegions(); }) },
      { id: 'contribute', label: 'Contribute timestamps', disabled: !app.host.contribution || state.detail?.title.source === 'HIANIME' || !['intro', 'recap', 'outro', 'post_credits'].includes(draft()!.type), action: () => run(async () => { const imdb = app.data.skip[`imdb:${titleKey(state.detail!.title)}`] || state.detail?.title.imdb; if (!imdb) throw new Error('Set an IMDb mapping first.'); await app.segments.submit(draft()!, imdb, state.episode); app.notify('Contribution sent.'); }) },
      { id: 'cancel', label: 'Cancel editing', action: closeEditor },
    ];
    if (menu === 'editor-type') return segmentTypes.map(t => ({ id: t.id, label: t.label, selected: draft()?.typeId === t.id, action: () => { setDraft(d => ({ ...d!, type: t.type, typeId: t.id })); player.menu('editor'); } }));
    return [];
  };
  const activeSegment = () => state.regions.filter(s => s.start <= (e()?.position || 0) && (e()?.position || 0) < s.end && s.end <= (e()?.duration || 0) && player.effectiveAction(s) === 'ASK').at(-1);
  const back = () => { if (draft()) closeEditor(); else player.menu(state.menu === 'settings' || state.menu === 'episodes' ? '' : state.menu === 'skip-actions' ? 'skip' : 'settings'); };
  const backKey = Symbol('player'); app.backHandlers.set(backKey, () => { if (draft()) { closeEditor(); return true; } return false; });
  onCleanup(() => { app.backHandlers.delete(backKey); if (draft()) editorResume = false; void player.cancel().catch(() => {}); });
  return <view width={1920} height={1080} onAny={() => { player.interact(); }}>
    <Show when={state.loading}><text skipFocus x={100} y={120} width={1700} height={80} fontSize={44} color={palette.foreground}>Preparing playback…</text></Show>
    <Show when={state.error}><view width={1920} height={1080} color={0x06152ee0} alpha={1} transition={{ alpha: motion.fade }}><view x={250} y={220} width={1420} height={650} color={palette.panel} borderRadius={20} border={{ width: 1, color: palette.line }} scale={1} transition={{ scale: motion.enter }}><Menu width={1380} height={620} title={failureMessages[state.error!]} options={[
      { id: 'retry', label: 'Try again', action: () => run(() => player.start(state.detail!, state.episode, state.version)) },
      { id: 'account', label: 'Check account in Settings', action: () => run(async () => { app.pendingPlayback = { detail: state.detail!, episode: state.episode, version: state.version }; await player.stop('/settings/account'); }) },
      { id: 'updates', label: 'Check for update', action: () => run(() => app.updates.check('player.response-v1')) },
      { id: 'report', label: 'Notify Dylan', disabled: state.error !== 'CHANGED' || !app.host.reportUrl || ['sending', 'sent'].includes(app.updates.state.report), action: () => run(() => app.updates.report('PLAYER')) },
      { id: 'back', label: 'Back', action: () => run(player.stop) },
    ]} /></view></view></Show>
    <Show when={state.chooser}><view width={1920} height={1080} color={0x06152ee0} alpha={1} transition={{ alpha: motion.fade }}><view x={250} y={220} width={1420} height={650} color={palette.panel} borderRadius={20} border={{ width: 1, color: palette.line }} scale={1} transition={{ scale: motion.enter }}><Menu width={1380} title="Choose anime version" options={[
      { id: 'dub', label: 'English dub', disabled: !state.available.includes('DUB'), action: () => run(() => player.choose('DUB')) },
      { id: 'sub', label: 'Japanese with subtitles', disabled: !state.available.includes('SUB'), action: () => run(() => player.choose('SUB')) },
      { id: 'back', label: 'Cancel', action: () => run(player.stop) },
    ]} /></view></view></Show>
    <Show when={state.stillWatching !== undefined}><view width={1920} height={1080} color={0x06152ee0} alpha={1} transition={{ alpha: motion.fade }}><view x={250} y={220} width={1420} height={650} color={palette.panel} borderRadius={20} border={{ width: 1, color: palette.line }} scale={1} transition={{ scale: motion.enter }}><Menu width={1380} title={`Still watching? ${state.stillWatching}s`} options={[
      { id: 'continue', label: 'Continue watching', action: () => run(player.continueWatching) }, { id: 'stop', label: 'Stop watching', action: () => run(player.stop) },
    ]} /></view></view></Show>
    <Show when={state.bufferAhead}><view width={1920} height={1080} color={0x06152ee0} alpha={1} transition={{ alpha: motion.fade }}><view x={250} y={220} width={1420} height={650} color={palette.panel} borderRadius={20} border={{ width: 1, color: palette.line }} scale={1} transition={{ scale: motion.enter }}><Menu width={1380} title={state.bufferReady ? 'Buffer ready' : state.bufferStalled ? 'Buffer stalled — connection may be slow' : 'Buffering ahead…'} options={[
      { id: 'play', label: 'Resume playback', action: () => run(() => player.buffer(false, true)) },
      { id: 'cancel', label: 'Cancel buffer-ahead', action: () => run(() => player.buffer(false, false)) },
    ]} /></view></view></Show>
    <Show when={e() && !state.error && !state.chooser && state.stillWatching === undefined && !state.bufferAhead}>
      <Show when={state.controls || state.menu || state.countdown !== undefined}>
        <view x={0} y={710} width={1920} height={370}
          linearGradient={{ colors: [0x00000000, 0x06152ecc, 0x06152ef5], stops: [0, 0.5, 1], angle: 0 }}
          alpha={1} transition={{ alpha: motion.fade }} />
        <Column x={96} y={750} width={1728} height={280} gap={14} autofocus
          alpha={1} transition={{ alpha: motion.fade }}>
          <text skipFocus width={1700} height={45} fontSize={32} color={palette.foreground}>{state.detail?.title.name || ''}{state.episode ? `  ·  ${state.episode.name}` : ''}</text>
          <view width={1728} height={55} autofocus onLeft={() => { player.preview((state.preview ?? e()!.position) - 10_000); return true; }} onRight={() => { player.preview((state.preview ?? e()!.position) + 10_000); return true; }} onEnter={() => { void player.commitSeek(); }}>
            <view y={12} width={1728} height={6} color={palette.line} />
            <view y={12} width={1728 * Math.min(1, e()!.buffered / (e()!.duration || 1))} height={6} color={palette.muted} />
            <view y={12} width={1728 * Math.min(1, (state.preview ?? e()!.position) / (e()!.duration || 1))} height={6} color={palette.accent} />
            <text skipFocus y={26} width={1728} height={30} fontSize={22} color={palette.muted}>{time(state.preview ?? e()!.position)} / {time(e()!.duration)}{state.preview !== undefined ? '  ·  OK to seek · Back to cancel' : ''}</text>
          </view>
          <Row width={1728} height={68} gap={14}>
            <Button label={e()?.playing ? 'Pause' : 'Play'} width={190} action={() => run(() => player.command(e()?.playing ? 'pause' : 'play'))} />
            <Button label="−10 seconds" width={235} action={() => run(() => player.command('seek', { position: Math.max(0, e()!.position - 10_000) }))} />
            <Button label="+10 seconds" width={235} action={() => run(() => player.command('seek', { position: Math.min(e()!.duration, e()!.position + 10_000) }))} />
            <Button label="Settings" id="player-settings" width={215} action={() => player.menu('settings')} />
            <Show when={state.detail?.episodes.length}><Button label="Episodes" width={215} action={() => player.menu('episodes')} /></Show>
            <Button label="Buffer ahead" width={255} action={() => run(() => player.buffer(true))} />
            <Show when={player.next()}><Button label="Next episode" width={250} action={() => run(player.nextEpisode)} /></Show>
          </Row>
          <Row width={1728} height={68} gap={14}>
            <Show when={activeSegment()}><Button label={`Skip ${activeSegment()!.typeId}`} action={() => run(() => player.skip(activeSegment()!))} /></Show>
            <Show when={state.countdown !== undefined}><Button label={`Next episode in ${state.countdown}s — Cancel`} width={590} action={player.cancelNext} /></Show>
          </Row>
        </Column>
      </Show>
      <Show when={state.menu}><view x={1130} y={60} width={750} height={700} color={palette.panel} borderRadius={[20, 0, 0, 20]} border={{ width: 1, color: palette.line }} transition={{ x: motion.slide }}>
        <Menu title={state.menu === 'editor' ? 'Edit region' : state.menu.replaceAll('-', ' ')} width={720} height={680} options={options()} back={back} />
      </view></Show>
    </Show>
  </view>;
}
