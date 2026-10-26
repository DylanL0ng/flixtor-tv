import { expect, it } from 'vitest';
import { getClipMask, gradientMaskBypass, gradientMaskOwners, updateClipMask } from '../src/ui/gradient-mask';

it('inherits bottom and top viewport fades through rail clips and clears reused clips', () => {
  const clips = { sx: new Int32Array([0, 76, 76]), sy: new Int32Array([0, 34, 34]), sw: new Int32Array([0, 1768, 1768]), sh: new Int32Array([0, 888, 440]) };
  gradientMaskOwners.set(10, [0, 24, 0, 96]); gradientMaskOwners.set(20, [56, 0, 56, 0]);
  try {
    updateClipMask(clips, 10, 1, 0, 1); updateClipMask(clips, 20, 2, 1, 1);
    const mask = getClipMask(clips, 2)!;
    expect([...mask.bounds]).toEqual([96, 54, 1824, 902]);
    expect([...mask.ramps]).toEqual([1 / 56, 1 / 96, 1 / 56, 1 / 24].map(Math.fround));
    clips.sy[2] = 0;
    updateClipMask(clips, 20, 2, 1, 1);
    expect(getClipMask(clips, 2)!.bounds[1]).toBe(54);
    updateClipMask(clips, 30, 2, 0, 1);
    expect([...getClipMask(clips, 2)!.ramps]).toEqual([0, 0, 0, 0]);
  } finally { gradientMaskOwners.delete(10); gradientMaskOwners.delete(20); }
});

it('bypasses every inherited fade for a focused subtree and restores masks on blur/reuse', () => {
  const clips = { sx: new Int32Array([0, 0, 10, 20]), sy: new Int32Array(4), sw: new Int32Array([0, 500, 200, 100]), sh: new Int32Array([0, 500, 200, 100]) };
  gradientMaskOwners.set(10, [56, 24, 56, 96]);
  try {
    updateClipMask(clips, 10, 1, 0, 2);
    gradientMaskBypass.add(20);
    updateClipMask(clips, 20, 2, 1, 2);
    updateClipMask(clips, 30, 3, 2, 2);
    expect([...getClipMask(clips, 2)!.ramps]).toEqual([0, 0, 0, 0]);
    expect([...getClipMask(clips, 3)!.ramps]).toEqual([0, 0, 0, 0]);
    const version = getClipMask(clips, 2)!.version;
    gradientMaskBypass.delete(20);
    updateClipMask(clips, 20, 2, 1, 2);
    expect([...getClipMask(clips, 2)!.ramps]).toEqual([1 / 112, 1 / 192, 1 / 112, 1 / 48].map(Math.fround));
    expect(getClipMask(clips, 2)!.version).toBeGreaterThan(version);
    updateClipMask(clips, 40, 2, 0, 2);
    expect([...getClipMask(clips, 2)!.ramps]).toEqual([0, 0, 0, 0]);
  } finally { gradientMaskOwners.delete(10); gradientMaskBypass.delete(20); }
});
