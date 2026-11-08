import type { Plugin } from 'vite';

/** SolidTV 1.7.1 otherwise keeps stale cards when data changes at the same offset. */
export function virtualRows(): Plugin {
  return {
    name: 'solidtv-refresh-virtual-window', enforce: 'pre',
    transform(source, id) {
      const path = id.split('?')[0].replaceAll('\\', '/');
      if (path.endsWith('/@solidtv/solid/dist/src/primitives/utils/withScrolling.js')) {
        const before = 'if (position !== nextPosition) {';
        if (source.split(before).length !== 2) throw new Error('SolidTV scrolling integration changed');
        // Reversing before the first animation frame must cancel the obsolete target,
        // even when the new target equals the current rendered position.
        return { code: source.replace(before, 'if (position !== nextPosition || targetPosition !== nextPosition) {'), map: null };
      }
      if (!path.endsWith('/@solidtv/solid/dist/src/primitives/Virtual.jsx')) return;
      const before = 'if (start !== prev.start || newSlice.length === 0) {';
      if (source.split(before).length !== 2) throw new Error('SolidTV virtual window integration changed');
      // Restore the same buffered window that directional navigation produces.
      // The upstream initial edge window is one slot ahead of the requested item.
      const edge = 'selected = c > startScrolling ? startScrolling : c;\n                        start = Math.max(0, c - startScrolling + 1);';
      if (source.split(edge).length !== 2 || source.split('atStart = c < startScrolling;').length !== 2 || source.split('if (delta === 0 && c > 0) {').length !== 2) throw new Error('SolidTV edge window integration changed');
      source = source.replace(edge, 'selected = Math.min(c, props.displaySize);\n                        start = Math.max(0, c - props.displaySize);')
        .replace('if (delta === 0 && c > 0) {', 'if (delta === 0) {')
        .replace('atStart = c < startScrolling;', 'atStart = c <= startScrolling;');
      return { code: `import { virtualWindowChanged } from '/src/ui/virtual-window.ts';\n${source.replace(before,
        'if (start !== prev.start || virtualWindowChanged(items(), newSlice, start, windowLength, effectiveWrap())) {')}`, map: null };
    },
  };
}
