import { readFileSync } from 'node:fs';
import { expect, it, vi } from 'vitest';
import { virtualRows } from '../tools/virtual-rows';

it.each([true, false])('retargets an unstarted scroll animation on reversal (horizontal=%s)', horizontal => {
  const path = new URL('../node_modules/@solidtv/solid/dist/src/primitives/utils/withScrolling.js', import.meta.url);
  const source = readFileSync(path, 'utf8');
  const transform = virtualRows().transform as (source: string, id: string) => { code: string };
  const patched = transform(source, path.pathname).code;
  const scroll = new Function(patched.replaceAll('export ', '') + '\nreturn withScrolling;')()(horizontal);
  const axis = horizontal ? 'x' : 'y';
  const set = vi.fn();
  const child = { x: 0, y: 0, width: 250, height: 480 };
  const node = { scroll: 'always', children: [child], width: 1728, height: 848, gap: 30,
    offset: 24, _screenOffset: 0, _initialPosition: 24, _targetPosition: -1200,
    lng: { stage: { root: { w: 1920, h: 1080 } } } };
  Object.defineProperty(node, axis, { get: () => 24, set });
  scroll(0, node, child, 1);
  expect(set).toHaveBeenCalledWith(24);
  expect(node._targetPosition).toBe(24);
});
