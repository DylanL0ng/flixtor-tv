import { createMemo, createSignal, For, Show } from 'solid-js';
import { createQuery } from '@tanstack/solid-query';
import { useSearchParams, useNavigate } from '@solidjs/router';
import { Column, Row } from '@solidtv/solid/primitives';
import { Button, Menu, palette } from '../../ui/components';
import { useApp } from '../../app/context';
import { episodeKey, failureOf, orderedEpisodes, series, titleKey } from '../../domain/models';
export function DetailPage() {
  const app = useApp(), navigate = useNavigate(); const [params] = useSearchParams(); const [season, setSeason] = createSignal<number>();
  const title = () => app.titleCache.get(String(params.title)) || (app.selected() && titleKey(app.selected()!) === params.title ? app.selected() : undefined);
  const query = createQuery(() => ({ queryKey: ['detail', params.title, app.authRevision()], enabled: !!title(), queryFn: ({ signal }) => title()!.source === 'HIANIME' ? app.anime.detail(title()!, signal) : app.flixtor.detail(title()!, signal) }));
  const detail = () => query.data;
  const seasons = createMemo(() => [...new Set(orderedEpisodes(detail()?.episodes || []).map(e => e.season))]);
  const start = (episode?: import('../../domain/models').Episode | null, resume = true) => app.run(async () => { if (!detail()) return; navigate('/player'); await app.playback!.start(detail()!, episode, undefined, resume); });
  return <Show when={title()} fallback={<Column width={1400} height={400} gap={24}><text skipFocus width={1300} height={70} fontSize={40}>Choose a title from the catalogue.</text><Button label="Home" action={() => navigate('/home')} /></Column>}>
    <Column width={1400} height={900} gap={24} autofocus>
      <text skipFocus width={1360} height={150} fontSize={62} maxLines={2}>{detail()?.title.name || title()?.name}</text>
      <text skipFocus width={1320} height={140} fontSize={28} color={palette.muted} maxLines={4}>{detail()?.title.synopsis || (query.isPending ? 'Loading details…' : '')}</text>
      <Show when={query.isError}><Button label={`Try again (${failureOf(query.error)})`} action={() => void query.refetch()} /></Show>
      <Show when={detail()}>
        <Row width={1400} height={68} gap={20}>
          <Show when={!series(detail()!.title)}><Button label="Play / Resume" action={() => start(detail()?.title.source === 'HIANIME' ? detail()?.episodes[0] : undefined)} /><Button label="Play from start" action={() => start(detail()?.title.source === 'HIANIME' ? detail()?.episodes[0] : undefined, false)} /></Show>
          <Button label={app.data.libraries[app.profileId()]?.favorites.some(t => titleKey(t) === titleKey(title()!)) ? 'Remove from My List' : 'Add to My List'} width={370} action={() => app.run(() => app.repo.toggle(app.profileId(), detail()!.title))} />
          <Button label="Back" width={200} action={() => navigate(-1)} />
        </Row>
        <Show when={seasons().length > 1}><Row width={1400} height={68} gap={12} scroll="edge"><For each={seasons()}>{s => <Button label={`Season ${s}`} width={220} selected={(season() || seasons()[0]) === s} action={() => setSeason(s)} />}</For></Row></Show>
        <Show when={detail()!.episodes.length}><Menu title="Episodes" height={400} width={1380} options={orderedEpisodes(detail()!.episodes).filter(e => e.season === (season() || seasons()[0])).map(e => {
          const v = app.data.libraries[app.profileId()]?.viewing.find(v => titleKey(v.title) === titleKey(title()!) && episodeKey(v.episode) === episodeKey(e));
          return { id: episodeKey(e), label: `${e.displayNumber || e.number}. ${e.name}`, value: v && v.duration > 0 ? `${Math.round(Math.min(1, v.position / v.duration) * 100)}% watched` : '', action: () => start(e) };
        })} /></Show>
      </Show>
    </Column>
  </Show>;
}
