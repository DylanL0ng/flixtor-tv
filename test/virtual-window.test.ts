import { expect, it } from 'vitest';
import { virtualWindowChanged } from '../src/ui/virtual-window';
it('invalidates replaced source data at an unchanged offset', () => {
  const a = { id: 1 }, b = { id: 2 }, c = { id: 3 };
  expect(virtualWindowChanged([a, b, c], [a, b], 0, 2, false)).toBe(false);
  expect(virtualWindowChanged([{ id: 1 }, b, c], [a, b], 0, 2, false)).toBe(true);
  expect(virtualWindowChanged([b, a, c], [a, b], 0, 2, false)).toBe(true);
  expect(virtualWindowChanged([a, b, c], [a, b], 0, 3, false)).toBe(true);
  expect(virtualWindowChanged([a], [a, b], 0, 2, false)).toBe(true);
});
it('retains unchanged windows on append and handles wrap boundaries', () => {
  expect(virtualWindowChanged([1, 2, 3, 4], [1, 2], 0, 2, false)).toBe(false);
  expect(virtualWindowChanged([1, 2, 3], [3, 1], 2, 2, true)).toBe(false);
  expect(virtualWindowChanged([1, 2, 4], [3, 1], 2, 2, true)).toBe(true);
  expect(virtualWindowChanged([], [], 0, 2, false)).toBe(false);
});
