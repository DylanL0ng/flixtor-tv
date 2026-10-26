import { episodeKey, finished, orderedEpisodes, titleKey, type Detail, type Title, type Viewing } from '../../domain/models';

export function catalogueKey(destination: string, query: string, sort: string, profile: string, auth: number) { return ['catalog', destination, query, sort, profile, auth] as const; }
export function distinctTitles(pages: Title[][]) {
  const seen = new Set<string>();
  return pages.flat().filter(title => { const key = titleKey(title); if (seen.has(key)) return false; seen.add(key); return true; });
}
export function nextCataloguePage(last: Title[], pages: Title[][], page: number) {
  if (!last.length) return undefined;
  const keys = new Set(pages.slice(0, -1).flat().map(titleKey));
  return last.some(title => !keys.has(titleKey(title))) ? page + 1 : undefined;
}
export function resumeViewing(viewing: Viewing[], title: Title) { return viewing.find(v => titleKey(v.title) === titleKey(title) && !finished(v)); }
export function resumeEpisode(detail: Detail, viewing?: Viewing) {
  const episodes = orderedEpisodes(detail.episodes);
  return episodes.find(e => viewing && episodeKey(e) === episodeKey(viewing.episode)) || episodes[0];
}
export function chunk<T>(items: T[], size: number) { return Array.from({ length: Math.ceil(items.length / size) }, (_, i) => items.slice(i * size, (i + 1) * size)); }
