// Lucide SVG paths from lucide-static@1.61.0, ISC license.
export type IconName = 'play' | 'pause' | 'back' | 'search' | 'settings' | 'plus' | 'check' | 'lock' | 'person' | 'episodes' | 'rewind' | 'forward' | 'close' | 'chevron' | 'delete' | 'home' | 'film' | 'tv' | 'anime' | 'list' | 'library' | 'favorite';
const paths: Record<IconName, string> = {
  play: "<path d=\"M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z\" />",
  pause: "<rect x=\"14\" y=\"3\" width=\"5\" height=\"18\" rx=\"1\" /> <rect x=\"5\" y=\"3\" width=\"5\" height=\"18\" rx=\"1\" />",
  back: "<path d=\"m12 19-7-7 7-7\" /> <path d=\"M19 12H5\" />",
  search: "<path d=\"m21 21-4.34-4.34\" /> <circle cx=\"11\" cy=\"11\" r=\"8\" />",
  settings: "<path d=\"M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915\" /> <circle cx=\"12\" cy=\"12\" r=\"3\" />",
  plus: "<path d=\"M5 12h14\" /> <path d=\"M12 5v14\" />",
  check: "<path d=\"M20 6 9 17l-5-5\" />",
  lock: "<circle cx=\"12\" cy=\"16\" r=\"1\" /> <rect x=\"3\" y=\"10\" width=\"18\" height=\"12\" rx=\"2\" /> <path d=\"M7 10V7a5 5 0 0 1 10 0v3\" />",
  person: "<circle cx=\"12\" cy=\"8\" r=\"5\" /> <path d=\"M20 21a8 8 0 0 0-16 0\" />",
  episodes: "<path d=\"M21 5H3\" /> <path d=\"M10 12H3\" /> <path d=\"M10 19H3\" /> <path d=\"M15 12.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997a1 1 0 0 1-1.517-.86z\" />",
  rewind: "<path d=\"M12 6a2 2 0 0 0-3.414-1.414l-6 6a2 2 0 0 0 0 2.828l6 6A2 2 0 0 0 12 18z\" /> <path d=\"M22 6a2 2 0 0 0-3.414-1.414l-6 6a2 2 0 0 0 0 2.828l6 6A2 2 0 0 0 22 18z\" />",
  forward: "<path d=\"M12 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 12 18z\" /> <path d=\"M2 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 2 18z\" />",
  close: "<path d=\"M18 6 6 18\" /> <path d=\"m6 6 12 12\" />",
  chevron: "<path d=\"m9 18 6-6-6-6\" />",
  delete: "<path d=\"M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z\" /> <path d=\"m12 9 6 6\" /> <path d=\"m18 9-6 6\" />",
  home: "<path d=\"M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8\" /> <path d=\"M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\" />",
  film: "<rect width=\"18\" height=\"18\" x=\"3\" y=\"3\" rx=\"2\" /> <path d=\"M7 3v18\" /> <path d=\"M3 7.5h4\" /> <path d=\"M3 12h18\" /> <path d=\"M3 16.5h4\" /> <path d=\"M17 3v18\" /> <path d=\"M17 7.5h4\" /> <path d=\"M17 16.5h4\" />",
  tv: "<path d=\"m17 2-5 5-5-5\" /> <rect width=\"20\" height=\"15\" x=\"2\" y=\"7\" rx=\"2\" />",
  anime: "<path d=\"m12.296 3.464 3.02 3.956\" /> <path d=\"M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3z\" /> <path d=\"M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\" /> <path d=\"m6.18 5.276 3.1 3.899\" />",
  list: "<path d=\"M12 7v6\" /> <path d=\"M15 10H9\" /> <path d=\"M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z\" />",
  library: "<rect width=\"8\" height=\"18\" x=\"3\" y=\"3\" rx=\"1\" /> <path d=\"M7 3v18\" /> <path d=\"M20.4 18.9c.2.5-.1 1.1-.6 1.3l-1.9.7c-.5.2-1.1-.1-1.3-.6L11.1 5.1c-.2-.5.1-1.1.6-1.3l1.9-.7c.5-.2 1.1.1 1.3.6Z\" />",
  favorite: "<path d=\"M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5\" />",
};
export function svgUrl(body: string, color = '#f5f8ff', viewBox = '0 0 24 24') {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="' + viewBox + '" fill="none" stroke="' + color + '" color="' + color + '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + body + '</svg>';
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}
const cache = new Map<string, string>();
export function icon(name: IconName, dark = false) { const key = name + ':' + dark; if (!cache.has(key)) cache.set(key, svgUrl(paths[name], dark ? '#0a1624' : '#f5f8ff')); return cache.get(key)!; }
export const avatarColors = [0x406e91ff, 0x826253ff, 0x526e68ff, 0x645c84ff, 0x8c664dff, 0x4d6d88ff, 0x795a6dff];
export function avatar(index: number) {
  return svgUrl('<circle cx="64" cy="48" r="19" fill="currentColor" stroke="none"/><path d="M23 112a41 41 0 0 1 82 0" fill="currentColor" stroke="none"/>', '#eaf3fa', '0 0 128 128');
}
