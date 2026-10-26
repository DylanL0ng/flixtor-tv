import { createEffect, createMemo, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { createQuery } from '@tanstack/solid-query';
import { useSearchParams, useNavigate } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import { Button, Menu, palette, Status, motion } from '../../ui/components';
import { reducedMotion } from '../../ui/theme';
import { Overlay } from '../../ui/Overlay';
import { EpisodeList } from '../../ui/Episodes';
import { useApp } from '../../app/context';
import { failureOf, orderedEpisodes, series, titleKey, type Episode } from '../../domain/models';
import { resumeEpisode, resumeViewing } from './catalog-state';
import { failureMessages } from '../../ui/copy';
import { narratePage } from '../../ui/narration';

export function DetailPage() {
  const app = useApp(), navigate = useNavigate(); const [params] = useSearchParams();
  const [season, setSeason] = createSignal<number>(), [seasonMenu, setSeasonMenu] = createSignal(false), [saving, setSaving] = createSignal(false);
  const [entered, setEntered] = createSignal(false); let entrance = 0;
  onMount(() => { entrance = requestAnimationFrame(() => setEntered(true)); });
  onCleanup(() => cancelAnimationFrame(entrance));
  const title = () => app.titleCache.get(String(params.title)) || [...(app.data.libraries[app.profileId()]?.favorites || []), ...(app.data.libraries[app.profileId()]?.viewing.map(v => v.title) || []), ...(app.selected() ? [app.selected()!] : [])].find(t => titleKey(t) === params.title);
  const query = createQuery(() => ({ queryKey: ['detail', params.title, app.profileId(), app.authRevision()], enabled: !!title(), queryFn: ({ signal }) => title()!.source === 'HIANIME' ? app.anime.detail(title()!, signal) : app.flixtor.detail(title()!, signal) }));
  const detail = () => query.data;
  narratePage(() => detail()?.title.name || title()?.name || 'Title details', () => detail()?.title.synopsis || '');
  const viewing = () => title() && resumeViewing(app.data.libraries[app.profileId()]?.viewing || [], title()!);
  const episodes = createMemo(() => orderedEpisodes(detail()?.episodes || []));
  const seasons = createMemo(() => [...new Set(episodes().map(e => e.season))]);
  const currentSeason = () => season() ?? viewing()?.episode?.season ?? seasons()[0];
  const initial = () => detail() ? resumeEpisode(detail()!, viewing()) : undefined;
  const saved = () => !!title() && app.data.libraries[app.profileId()]?.favorites.some(t => titleKey(t) === titleKey(title()!));
  createEffect(() => { app.setStage(detail()?.title || title()); });
  const start = (episode?: Episode | null, resume = true) => app.run(async () => {
    const data = detail(); if (!data) return;
    navigate('/player'); await app.playback!.start(data, episode, undefined, resume);
  });
  return <Show when={title()} fallback={<Column width={1728} height={900} gap={30}><Button label="Back to browsing" icon="back" autofocus action={() => navigate('/home')} /><Status message="Choose a title from the catalogue to see its details." /></Column>}>
    <Column width={1728} height={972} gap={28} scroll="edge" alpha={entered() || reducedMotion() ? 1 : 0.35} transition={{ alpha: motion.enter }}>
      <Column width={1728} height={570} gap={20} scroll="none">
        <Button label="Back" id="detail-back" icon="back" width={170} quiet action={() => navigate(-1)} />
        <text skipFocus width={1150} height={165} fontSize={76} fontFamily="Roboto700" maxLines={2}>{detail()?.title.name || title()?.name}</text>
        <text skipFocus width={1200} height={38} fontSize={26} color={palette.muted} maxLines={1}>{[title()?.year, title()?.genres, title()?.rating ? `Rating ${title()?.rating}` : '', title()?.source === 'HIANIME' ? 'Anime' : ''].filter(Boolean).join('   ·   ')}</text>
        <text skipFocus width={1000} height={115} fontSize={28} color={palette.muted} maxLines={3}>{detail()?.title.synopsis || (query.isPending ? 'Loading details…' : '')}</text>
        <Show when={query.isError}><Row width={1400} height={68} gap={20}><Button label="Try again" autofocus action={() => void query.refetch()} /><Status width={1000} message={failureMessages[failureOf(query.error)]} error /></Row></Show>
        <Show when={detail()}><Row width={1728} height={72} gap={18}>
          <Button id="detail-play" label={viewing() ? `Resume${viewing()?.episode ? ` · Episode ${viewing()!.episode!.displayNumber || viewing()!.episode!.number}` : ''}` : 'Play'} icon="play" width={viewing()?.episode ? 370 : 230} primary autofocus action={() => start(initial())} />
          <Button label={saved() ? 'In My List' : 'Add to My List'} icon={saved() ? 'check' : 'plus'} width={310} disabled={saving()} selected={saved()} action={() => app.run(async () => {
            setSaving(true); try { await app.repo.toggle(app.profileId(), detail()!.title); app.notify(saved() ? 'Added to My List.' : 'Removed from My List.'); } finally { setSaving(false); }
          })} />
          <Button label="Play from start" width={270} quiet action={() => start(initial(), false)} />
        </Row></Show>
      </Column>
      <Show when={episodes().length}><Column width={1728} height={650} gap={24} scroll="none">
        <Row width={1728} height={68} gap={30}><text skipFocus width={900} height={54} fontSize={38} fontFamily="Roboto700">Episodes</text>
          <Button label={`Season ${currentSeason()}`} width={240} selected action={() => setSeasonMenu(true)} />
        </Row>
        <Show when={currentSeason()} keyed>{current => <EpisodeList title={title()!} episodes={episodes().filter(e => e.season === current)} width={1450} height={530}
          viewing={app.data.libraries[app.profileId()]?.viewing || []} initial={season() === undefined ? initial() : undefined} autofocus={season() !== undefined} play={episode => start(episode)} />}</Show>
      </Column></Show>
    </Column>
    <Show when={seasonMenu()}><Overlay title="Choose season" close={() => setSeasonMenu(false)} width={760} height={740}>
      <Menu width={680} height={668} title="Choose season" options={seasons().map(s => ({ id: String(s), label: `Season ${s}`, selected: currentSeason() === s, action: () => { setSeason(s); setSeasonMenu(false); } }))} />
    </Overlay></Show>
  </Show>;
}
