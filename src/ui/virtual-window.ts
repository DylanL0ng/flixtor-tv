/** Reuse a virtual window only while it still contains the active source items. */
export function virtualWindowChanged<T>(items: readonly T[], window: readonly T[], start: number, length: number, wrap: boolean) {
  const size = wrap ? length : Math.min(length, Math.max(0, items.length - start));
  return window.length !== size || window.some((item, index) => item !== items[wrap ? ((start + index) % items.length + items.length) % items.length : start + index]);
}
