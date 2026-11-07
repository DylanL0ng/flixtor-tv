import { createEffect, createMemo, createSignal, onCleanup, onMount, Show } from 'solid-js';
import { createQuery } from '@tanstack/solid-query';
import { useSearchParams, useNavigate } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import { Button, Menu, palette, Status, motion } from '../../ui/components';
import { reducedMotion } from '../../ui/theme';
import { Overlay } from '../../ui/Overlay';
import { EpisodeList } from '../../ui/Episodes';
import { useApp } from '../../app/context';
import { orderedEpisodes, series, titleKey, type Episode } from '../../domain/models';
import { resumeEpisode, resumeViewing } from './catalog-state';
import { ContentError } from '../../ui/ContentError';
import { narratePage } from '../../ui/narration';
import { METADATA_TTL, matchMovie } from '../../services/tmdb';
import { MetadataSections } from './Discovery';

export function DetailPage() {
  const app = useApp(), navigate = useNavigate(); const [params] = useSearchParams();
  const [season, setSeason] = createSignal<number>(), [seasonMenu, setSeasonMenu] = createSignal(false), [saving, setSaving] = createSignal(false);
  const [entered, setEntered] = createSignal(false); let entrance = 0;
  onMount(() => { entrance = requestAnimationFrame(() => setEntered(true)); });
  onCleanup(() => cancelAnimationFrame(entrance));
  const originalTitle = () => app.titleCache.get(String(params.title)) || [...(app.data.libraries[app.profileId()]?.favorites || []), ...(app.data.libraries[app.profileId()]?.viewing.map(v => v.title) || []), ...(app.selected() ? [app.selected()!] : [])].find(t => titleKey(t) === params.title);
  const creditTitle = originalTitle;
  const identified = createQuery(() => ({ queryKey: ['tmdb', 'identify', creditTitle()?.imdb || params.imdb, creditTitle()?.name || params.name, creditTitle()?.year || params.year],
    enabled: !!app.host.tmdb && !params.tmdb && (!!params.imdb || !!originalTitle() && originalTitle()!.source !== 'HIANIME' && !series(originalTitle()!)), staleTime: METADATA_TTL, retry: false,
    queryFn: ({ signal }) => app.tmdb.identify(creditTitle() || { imdb: String(params.imdb || ''), name: String(params.name || ''), year: String(params.year || '') }, signal) }));
  const identity = () => identified.isSuccess ? identified.data : undefined;
  const metadata = createQuery(() => ({ queryKey: ['tmdb', 'detail', Number(params.tmdb) || identity()?.tmdbId],
    enabled: !!app.host.tmdb && !!(Number(params.tmdb) || identity()?.tmdbId), staleTime: METADATA_TTL, retry: false,
    queryFn: ({ signal }) => app.tmdb.detail(Number(params.tmdb) || identity()!.tmdbId, signal) }));
  const meta = () => metadata.isSuccess ? metadata.data : undefined;
  const availability = createQuery(() => ({ queryKey: ['availability', meta()?.title.tmdbId, app.profileId(), app.authRevision()],
    enabled: !originalTitle() && !!meta(), retry: false,
    queryFn: async ({ signal }) => matchMovie(meta()!.title, await app.flixtor.search(meta()!.title.name, 1, signal)) || null }));
  const title = () => originalTitle() || (availability.isSuccess ? availability.data : undefined) || undefined;
  const query = createQuery(() => ({ queryKey: ['detail', title() ? titleKey(title()!) : params.title || `tmdb:${params.tmdb || params.imdb || ''}`, app.profileId(), app.authRevision()], enabled: !!title(), queryFn: ({ signal }) => title()!.source === 'HIANIME' ? app.anime.detail(title()!, signal) : app.flixtor.detail(title()!, signal) }));
  const detail = () => query.isSuccess ? query.data : undefined;
  const displayTitle = () => detail()?.title || title() || meta()?.title;
  narratePage(() => displayTitle()?.name || 'Title details', () => displayTitle()?.synopsis || '');
  const viewing = () => title() && resumeViewing(app.data.libraries[app.profileId()]?.viewing || [], title()!);
  const episodes = createMemo(() => orderedEpisodes(detail()?.episodes || []));
  const seasons = createMemo(() => [...new Set(episodes().map(e => e.season))]);
  const currentSeason = () => season() ?? viewing()?.episode?.season ?? seasons()[0];
  const initial = () => detail() ? resumeEpisode(detail()!, viewing()) : undefined;
  const saved = () => !!title() && app.data.libraries[app.profileId()]?.favorites.some(t => titleKey(t) === titleKey(title()!));
  createEffect(() => { app.setStage(displayTitle()); });
  const start = (episode?: Episode | null, resume = true) => app.run(async () => {
    const data = detail(); if (!data) return;
    navigate('/player'); await app.playback!.start(data, episode, undefined, resume);
  });
  return <Show when={displayTitle()} fallback={<Column width={1728} height={900} gap={30}><Button label="Back to browsing" icon="back" autofocus action={() => navigate(-1)} /><Status message={metadata.isFetching || identified.isFetching ? 'Loading movie details…' : 'Movie details unavailable. Return to browsing or try again.'} /><Show when={metadata.error || identified.error}><Button label="Retry metadata" action={() => { void identified.refetch(); void metadata.refetch(); }} /></Show></Column>}>
    <Column width={1728} height={972} gap={28} scroll="edge" alpha={entered() || reducedMotion() ? 1 : 0.35} transition={{ alpha: motion.enter }}>
      <Column width={1728} height={570} gap={20} scroll="none">
        <Button label="Back" id="detail-back" autofocus={!title()} icon="back" width={170} quiet action={() => navigate(-1)} />
        <text skipFocus width={1150} height={165} fontSize={76} fontFamily="Roboto700" maxLines={2}>{displayTitle()?.name}</text>
        <text skipFocus width={1200} height={38} fontSize={26} color={palette.muted} maxLines={1}>{[displayTitle()?.year, title()?.genres || meta()?.title.genres.join(' · '), displayTitle()?.rating ? `Rating ${displayTitle()?.rating}` : '', title()?.source === 'HIANIME' ? 'Anime' : ''].filter(Boolean).join('   ·   ')}</text>
        <text skipFocus width={1000} height={115} fontSize={28} color={palette.muted} maxLines={3}>{displayTitle()?.synopsis || (query.isFetching ? 'Loading details…' : '')}</text>
        <Show when={!originalTitle() && !detail()}><Row width={1728} height={72} gap={20}><Status width={1100} message={availability.isFetching || query.isFetching ? 'Checking FlixTor availability…' : availability.error ? 'Could not check FlixTor availability.' : 'Not available on FlixTor. Explore the cast and related movies below.'} /><Show when={availability.error}><Button label="Check again" action={() => void availability.refetch()} /></Show></Row></Show>
        <ContentError screenKey={JSON.stringify([params.title, app.profileId(), app.authRevision()])} operations={[{ id: 'detail', error: query.error, fetching: query.isFetching, retry: () => query.refetch() }]} />
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
      <Show when={meta()}>{data => <MetadataSections metadata={data()} />}</Show>
      <Show when={identified.error || metadata.error}><Row width={1728} height={80} gap={20}><Status width={1100} message="Cast and related movies could not load." /><Button label="Retry discovery" action={() => { if (identified.error) void identified.refetch(); else void metadata.refetch(); }} /></Row></Show>
    </Column>
    <Show when={seasonMenu()}><Overlay title="Choose season" close={() => setSeasonMenu(false)} width={760} height={740}>
      <Menu width={680} height={668} title="Choose season" options={seasons().map(s => ({ id: String(s), label: `Season ${s}`, selected: currentSeason() === s, action: () => { setSeason(s); setSeasonMenu(false); } }))} />
    </Overlay></Show>
  </Show>;
}
