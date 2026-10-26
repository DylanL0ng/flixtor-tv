// Viewport owners only: scrolling children keep their existing shader/material.
export const gradientMaskOwners = new Map<number, readonly [left: number, top: number, right: number, bottom: number]>();
export const gradientMaskBypass = new Set<number>();
export const viewportClipMargin = 20;

interface ClipStore {
  sx: Int32Array; sy: Int32Array; sw: Int32Array; sh: Int32Array;
}
interface ClipMask { bounds: Float32Array; ramps: Float32Array; version: number }
const clipMasks = new WeakMap<ClipStore, ClipMask[]>();
export function getClipMask(clips: ClipStore, clip: number) { return clipMasks.get(clips)?.[clip]; }

/** Called only when the renderer updates a clip, including inherited transforms. */
export function updateClipMask(clips: ClipStore, owner: number, clip: number, parent: number, ratio: number) {
  let masks = clipMasks.get(clips);
  if (!masks) { masks = []; clipMasks.set(clips, masks); }
  const mask = masks[clip] ||= { bounds: new Float32Array(4), ramps: new Float32Array(4), version: 0 };
  const inherited = parent ? masks[parent] : undefined;
  for (let edge = 0; edge < 4; edge++) {
    mask.bounds[edge] = inherited?.bounds[edge] || 0;
    mask.ramps[edge] = inherited?.ramps[edge] || 0;
  }
  const fades = gradientMaskOwners.get(owner);
  if (fades) {
    const [left, top, right, bottom] = fades;
    const margin = viewportClipMargin * ratio;
    if (left) { mask.bounds[0] = clips.sx[clip] + margin; mask.ramps[0] = 1 / (left * ratio); }
    if (bottom) { mask.bounds[1] = clips.sy[clip] + margin; mask.ramps[1] = 1 / (bottom * ratio); }
    if (right) { mask.bounds[2] = clips.sx[clip] + clips.sw[clip] - margin; mask.ramps[2] = 1 / (right * ratio); }
    if (top) { mask.bounds[3] = clips.sy[clip] + clips.sh[clip] - margin; mask.ramps[3] = 1 / (top * ratio); }
  }
  if (gradientMaskBypass.has(owner)) mask.ramps.fill(0);
  mask.version++;
}

export function maskFragment(fragment: string): string {
  if (!/void\s+main\s*\(\s*(?:void)?\s*\)/.test(fragment)) throw new Error('Unsupported SolidTV fragment shader');
  return fragment.replace(/void\s+main\s*\(\s*(?:void)?\s*\)/, 'void unmaskedMain()') + `
uniform vec4 u_viewportMask;
uniform vec4 u_viewportRamps;
void main() {
  unmaskedMain();
  vec4 distance = vec4(gl_FragCoord.xy - u_viewportMask.xy, u_viewportMask.zw - gl_FragCoord.xy);
  vec4 fade = clamp(distance * u_viewportRamps + step(u_viewportRamps, vec4(0.0)), 0.0, 1.0);
  gl_FragColor *= fade.x * fade.y * fade.z * fade.w;
}
`;
}
