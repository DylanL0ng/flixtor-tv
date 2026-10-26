import { expect, test } from '@playwright/test';
import { maskFragment } from '../../src/ui/gradient-mask';
import { dehydrate, QueryClient } from '@tanstack/solid-query';

for (const viewport of [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }]) {
  test(`media rail masks register and clean up at ${viewport.height}p`, async ({ page }, info) => {
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    const queries = new QueryClient();
    for (const catalog of ['top', 'imdbRating']) queries.setQueryData(['discovery', 'movie', catalog], Array.from({ length: 18 }, (_, index) => ({
      id: `tt${catalog}${index}`, type: 'movie', name: `${catalog} ${index + 1}`, genres: [], year: '2026',
    })));
    const snapshot = JSON.stringify({ version: 1, savedAt: Date.now(), state: dehydrate(queries) }); queries.clear();
    await page.addInitScript(snapshot => localStorage.setItem('flixtor-fixture:querySnapshot', snapshot), snapshot);
    await page.addInitScript(() => {
      const locations = new WeakMap<WebGLUniformLocation, string>();
      const proto = WebGLRenderingContext.prototype;
      const get = proto.getUniformLocation, set = proto.uniform4fv;
      Object.assign(window, { maskUploads: [] });
      proto.getUniformLocation = function(program, name) {
        const location = get.call(this, program, name); if (location) locations.set(location, name); return location;
      };
      proto.uniform4fv = function(location, values) {
        if (location && locations.get(location) === 'u_viewportRamps') (window as unknown as { maskUploads: number[][] }).maskUploads.push(Array.from(values));
        set.call(this, location, values);
      };
    });
    await page.setViewportSize(viewport); await page.goto('/');
    const profile = page.getByRole('button', { name: 'My profile', exact: true });
    await profile.focus(); await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/home/);
    const maskCount = () => page.evaluate(async () => {
      const path = '/src/ui/gradient-mask.ts';
      return (await import(path)).gradientMaskOwners.size;
    });
    const selectedMask = () => page.evaluate(async () => {
      const focusPath = '/src/ui/focus.ts', maskPath = '/src/ui/gradient-mask.ts';
      const layoutPath = '/node_modules/@solidtv/renderer/dist/src/core/nodes/NodeLayout.js';
      const { activeElement, nodeRect } = await import(focusPath);
      const { getClipMask, gradientMaskBypass } = await import(maskPath);
      const { I_CLIP, I_STRIDE } = await import(layoutPath);
      const node = activeElement(), native = node?.lng;
      if (!native || !gradientMaskBypass.has(native.id)) return undefined;
      const renderer = native.renderer;
      const clip = renderer.nodes.i32[native.id * I_STRIDE + I_CLIP];
      const parentClip = renderer.nodes.i32[node.parent.lng.id * I_STRIDE + I_CLIP];
      return { ramps: Array.from(getClipMask(renderer.clips, clip)?.ramps || []),
        parentRamps: Array.from(getClipMask(renderer.clips, parentClip)?.ramps || []),
        clipped: nodeRect(node), full: nodeRect(node, false), count: gradientMaskBypass.size };
    });
    await expect.poll(maskCount).toBeGreaterThan(0);
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('button[data-focused="true"]')).toHaveText('top 1');
    await page.waitForTimeout(250);
    await expect.poll(async () => (await selectedMask())?.ramps).toEqual([0, 0, 0, 0]);
    const firstMask = (await selectedMask())!;
    expect(firstMask.count).toBe(1);
    expect(firstMask.parentRamps.some(value => Number(value) > 0)).toBe(true);
    expect(firstMask.clipped.width).toBeCloseTo(firstMask.full.width, 1);
    await page.screenshot({ path: info.outputPath('media-mask-first.png') });
    await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight');
    await expect(page.locator('button[data-focused="true"]')).toHaveText('top 3');
    await page.waitForTimeout(700);
    const uploads = await page.evaluate(() => (window as unknown as { maskUploads: number[][] }).maskUploads);
    expect(uploads.some(ramps => ramps.every(value => value > 0))).toBe(true);
    const selected = await page.getByRole('button', { name: 'top 3', exact: true }).boundingBox();
    const peer = await page.getByRole('button', { name: 'top 4', exact: true }).boundingBox();
    const navigation = await page.getByRole('button', { name: 'Movies', exact: true }).boundingBox();
    await page.screenshot({ path: info.outputPath('media-mask.png') });
    expect(selected!.height).toBeCloseTo(466 * viewport.height / 1080, 0);
    expect(selected!.width).toBeGreaterThan(peer!.width);
    expect(selected!.y).toBeGreaterThan(navigation!.y + navigation!.height + 12);
    await page.screenshot({ path: info.outputPath('media-mask.png') });
    for (let index = 0; index < 15; index++) await page.keyboard.press('ArrowRight');
    await expect(page.locator('button[data-focused="true"]')).toHaveText('top 18');
    await page.waitForTimeout(400);
    const lastMask = (await selectedMask())!;
    expect(lastMask.ramps).toEqual([0, 0, 0, 0]);
    expect(lastMask.clipped.width).toBeCloseTo(lastMask.full.width, 1);
    expect(lastMask.clipped.height).toBeCloseTo(lastMask.full.height, 1);
    await page.screenshot({ path: info.outputPath('media-mask-end.png') });
    const movies = page.getByRole('button', { name: 'Movies', exact: true });
    await movies.focus(); await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/movies/);
    await expect.poll(maskCount).toBe(4);
    const list = page.getByRole('button', { name: 'My List', exact: true });
    await list.focus(); await page.keyboard.press('Enter');
    await expect.poll(maskCount).toBe(1);
    await expect.poll(() => page.evaluate(async () => {
      const path = '/src/ui/gradient-mask.ts';
      return (await import(path)).gradientMaskBypass.size;
    })).toBe(0);
    expect(errors).toEqual([]);
  });
}

test('viewport mask multiplies premultiplied RGBA and stays fixed as content moves', async ({ page }) => {
  await page.goto('/');
  const samples = await page.evaluate(fragment => {
    const canvas = document.createElement('canvas'); canvas.width = 100; canvas.height = 100;
    const gl = canvas.getContext('webgl', { premultipliedAlpha: true })!;
    const compile = (type: number, source: string) => {
      const shader = gl.createShader(type)!; gl.shaderSource(shader, source); gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader)!);
      return shader;
    };
    const vertex = compile(gl.VERTEX_SHADER, 'attribute vec2 p; void main(){gl_Position=vec4(p,0.,1.);}');
    const pixel = compile(gl.FRAGMENT_SHADER, fragment);
    const program = gl.createProgram()!; gl.attachShader(program, vertex); gl.attachShader(program, pixel); gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program)!);
    gl.useProgram(program);
    const buffer = gl.createBuffer()!; gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const position = gl.getAttribLocation(program, 'p'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    gl.uniform4f(gl.getUniformLocation(program, 'u_viewportMask'), 0, 0, 100, 100);
    gl.uniform4f(gl.getUniformLocation(program, 'u_viewportRamps'), 0.1, 0, 0.1, 0);
    const offset = gl.getUniformLocation(program, 'contentOffset');
    const draw = (x: number) => {
      gl.uniform1f(offset, x); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const rgba = new Uint8Array(400); gl.readPixels(0, 50, 100, 1, gl.RGBA, gl.UNSIGNED_BYTE, rgba);
      return [0, 5, 10, 50, 89, 94, 99].map(index => [...rgba.slice(index * 4, index * 4 + 4)]);
    };
    const initial = draw(0), moved = draw(20);
    gl.uniform4f(gl.getUniformLocation(program, 'u_viewportRamps'), 0, 0.1, 0, 0.1);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const verticalPixels = new Uint8Array(400); gl.readPixels(50, 0, 1, 100, gl.RGBA, gl.UNSIGNED_BYTE, verticalPixels);
    const verticalAlpha = [0, 5, 10, 50, 89, 94, 99].map(index => verticalPixels[index * 4 + 3]);
    gl.deleteBuffer(buffer); gl.deleteProgram(program); gl.deleteShader(vertex); gl.deleteShader(pixel);
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return { initial, moved, verticalAlpha };
  }, maskFragment('precision mediump float; uniform float contentOffset; void main(){gl_FragColor=vec4(0.25 + contentOffset * 0.001,0.125,0.0,0.5);}'));
  const alpha = samples.initial.map(rgba => rgba[3]);
  expect(alpha[0]).toBeLessThan(10);
  expect(alpha[1]).toBeGreaterThan(60); expect(alpha[1]).toBeLessThan(80);
  expect(alpha.slice(2, 5)).toEqual([128, 128, 128]);
  expect(alpha).toEqual([...alpha].reverse());
  expect(samples.moved.map(rgba => rgba[3])).toEqual(alpha);
  expect(samples.verticalAlpha).toEqual(alpha);
  expect(samples.initial[3].slice(0, 3)).toEqual([64, 32, 0]);
  expect(samples.initial[0][0]).toBeLessThan(5);
});
