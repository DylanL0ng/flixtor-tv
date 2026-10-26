import type { Plugin } from 'vite';

// Renderer 2.0.7 has no subtree masks/RTT. Extend its existing draw pass so
// textures, rounded materials and SDF text all get the same viewport alpha.
// Fail closed on renderer upgrades rather than silently losing the mask.
export function gradientMask(): Plugin {
  return {
    name: 'solidtv-viewport-gradient-mask',
    enforce: 'pre',
    transform(source, id) {
      const path = id.split('?')[0].replaceAll('\\', '/');
      if (path.endsWith('/@solidtv/renderer/dist/src/core/scene/ScenePass.js')) {
        const before = 'if (parentClip !== 0 && c.valid[parentClip] === 1) {';
        if (source.split(before).length !== 2) throw new Error('SolidTV gradient mask clip integration changed');
        return { code: `import { updateClipMask } from '/src/ui/gradient-mask.ts';\n${source.replace(before, 'updateClipMask(c, id, rec, parentClip, view.pixelRatio);\n        ' + before)}`, map: null };
      }
      if (!path.endsWith('/@solidtv/renderer/dist/src/core/gl/GlBackend.js')) return;
      const replace = (before: string, after: string) => {
        if (source.split(before).length !== 2) throw new Error(`SolidTV gradient mask integration changed: ${before}`);
        source = source.replace(before, after);
      };
      replace('new Program(ctx, vertex, fragment, name)', 'new Program(ctx, vertex, maskFragment(fragment), name)');
      replace('const resolution = program.system[U_RESOLUTION];', `program.viewportMaskLocation = ctx.uniformLocation(program.handle, 'u_viewportMask');
        program.viewportRampsLocation = ctx.uniformLocation(program.handle, 'u_viewportRamps');
        program.viewportMaskVersion = -1;
        const resolution = program.system[U_RESOLUTION];`);
      replace('const clip = opClip[i];', `const clip = opClip[i];
            const mask = clip !== 0 && clips.valid[clip] === 1 ? getClipMask(clips, clip) : undefined;
            if (program.viewportMask !== mask || program.viewportMaskVersion !== (mask?.version ?? 0)) {
                if (mask) {
                    ctx.uniform4fv(program.viewportMaskLocation, mask.bounds);
                    ctx.uniform4fv(program.viewportRampsLocation, mask.ramps);
                } else {
                    ctx.uniform4f(program.viewportMaskLocation, 0, 0, 0, 0);
                    ctx.uniform4f(program.viewportRampsLocation, 0, 0, 0, 0);
                }
                program.viewportMask = mask;
                program.viewportMaskVersion = mask?.version ?? 0;
            }`);
      return { code: `import { getClipMask, maskFragment } from '/src/ui/gradient-mask.ts';\n${source}`, map: null };
    },
  };
}
