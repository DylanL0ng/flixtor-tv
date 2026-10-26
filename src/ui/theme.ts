import { createSignal } from 'solid-js';

export const palette = {
  midnight: 0x080c14ff, ground: 0x080c14ff, panel: 0x28313dd9, panelSolid: 0x18212eff,
  surface: 0x28313dd9, accent: 0xb8e9ffff, focus: 0xb8e9ffff, muted: 0xb7c4d5ff,
  foreground: 0xf5f8ffff, text: 0xf5f8ffff, white: 0xffffffff, black: 0x000000ff,
  ink: 0x0a1624ff, danger: 0xffb4adff, line: 0x768ba44d,
};
export const motion = {
  focus: { duration: 160, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
  lift: { duration: 180, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
  enter: { duration: 300, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
  slide: { duration: 240, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
  fade: { duration: 360, easing: 'ease-out' },
} as const;
export const [reducedMotion, setReducedMotion] = createSignal(false);
export const [reducedTransparency, setReducedTransparency] = createSignal(false);
export const glassColor = () => reducedTransparency() ? palette.panelSolid : palette.panel;
export const time = (milliseconds: number) => {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  return seconds >= 3600 ? `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
};
