export type IconName = 'play' | 'pause' | 'back' | 'search' | 'settings' | 'plus' | 'check' | 'lock' | 'person' | 'episodes' | 'rewind' | 'forward' | 'close' | 'chevron' | 'delete';
const paths: Record<IconName, string> = {
  play: '<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M8 5v14M16 5v14" stroke-width="4"/>',
  back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  settings: '<circle cx="12" cy="12" r="3.5"/><path d="m10 3 1-1h2l1 1 .5 2 2 .8 2-.5 1.5 2.5-1.5 1.5v2.4l1.5 1.5-1.5 2.5-2-.5-2 .8-.5 2-1 1h-2l-1-1-.5-2-2-.8-2 .5L3.5 13l1.5-1.5V9L3.5 7.5 5 5l2 .5 2-.8Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>', check: '<path d="m5 12 4.5 4.5L20 6"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>',
  person: '<circle cx="12" cy="8" r="4"/><path d="M4 22v-3a8 8 0 0 1 16 0v3"/>',
  episodes: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M8 4v16M3 10h5M3 15h5m5-6 5 3-5 3Z"/>',
  rewind: '<path d="m11 6-8 6 8 6Zm10 0-8 6 8 6Z"/>', forward: '<path d="m3 6 8 6-8 6Zm10 0 8 6-8 6Z"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>', chevron: '<path d="m9 5 7 7-7 7"/>',
  delete: '<path d="M9 5h12v14H9L2 12Zm4 4 5 6m0-6-5 6"/>',
};
export function svgUrl(body: string, color = '#f5f8ff', viewBox = '0 0 24 24') {
  return `data:image/svg+xml;base64,${btoa(`<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="${viewBox}" fill="none" stroke="${color}" color="${color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`)}`;
}
const cache = new Map<string, string>();
export function icon(name: IconName, dark = false) { const key = `${name}:${dark}`; if (!cache.has(key)) cache.set(key, svgUrl(paths[name], dark ? '#0a1624' : '#f5f8ff')); return cache.get(key)!; }
export const avatarColors = [0x406e91ff, 0x826253ff, 0x526e68ff, 0x645c84ff, 0x8c664dff, 0x4d6d88ff, 0x795a6dff];
export function avatar(index: number) {
  return svgUrl('<circle cx="64" cy="48" r="19" fill="currentColor" stroke="none"/><path d="M23 112a41 41 0 0 1 82 0" fill="currentColor" stroke="none"/>', '#eaf3fa', '0 0 128 128');
}
