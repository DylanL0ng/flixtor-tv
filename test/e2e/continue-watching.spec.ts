import { expect, test } from '@playwright/test';
import { dehydrate, QueryClient } from '@tanstack/solid-query';

test('Continue Watching stays visible after repeated fast and settled trips through lower rails', async ({ page }, info) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const toDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function(...args) {
      if (sessionStorage.getItem('flixtor-fixture:failBlur') === 'true') throw new Error('Fixture backdrop processing failure');
      return toDataURL.apply(this, args);
    };
    localStorage.setItem('flixtor-fixture:art', 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAASCAIAAAC1qksFAAAAH0lEQVR4nGPQiCqlKWIYtWDUglELRi0YtWDUAnpYAAD6TCvfHVY0xgAAAABJRU5ErkJggg==');
    localStorage.setItem('flixtor-fixture:app', JSON.stringify({ schema: 1,
      profiles: [{ id: 'default', name: 'My profile', avatar: 0, quality: 0, autoplay: true, largerBuffer: false, locked: false }],
      libraries: { default: { favorites: [], viewing: Array.from({ length: 8 }, (_, index) => ({
        title: { id: String(index + 1), path: `/watch/movie/${index + 1}/resume`, name: `Resume ${index + 1}`, year: '2026', poster: `https://fixture/resume-${index}.png`, backdrop: `https://fixture/backdrop-${index}.png` },
        position: 30_000, duration: 120_000,
      })) } }, skip: {}, fit: { horizontal: 5, vertical: 5 } }));
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'My profile', exact: true }).focus(); await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/home/);
  const focused = page.locator('button[data-focused="true"]');
  await page.keyboard.press('ArrowDown');
  // Continue Watching has a header action before its media rail.
  if (await focused.textContent() === 'View Continue Watching') await page.keyboard.press('ArrowDown');
  await expect(focused).toHaveText('Resume 1');
  // Let fresh server results replace discovery data while a lower rail owns focus.
  const queries = new QueryClient();
  for (const catalog of ['top', 'imdbRating']) queries.setQueryData(['discovery', 'movie', catalog], Array.from({ length: 18 }, (_, index) => ({
    id: `tt${catalog}${index}`, type: 'movie', name: `Cached ${catalog} ${index + 1}`, genres: [],
  })), { updatedAt: Date.now() - 120_000 });
  const snapshot = JSON.stringify({ version: 1, savedAt: Date.now(), state: dehydrate(queries) }); queries.clear();
  await page.addInitScript(snapshot => {
    localStorage.setItem('flixtor-fixture:querySnapshot', snapshot);
    localStorage.setItem('flixtor-fixture:httpDelay', '2000');
  }, snapshot);
  await page.reload();
  await page.getByRole('button', { name: 'My profile', exact: true }).focus(); await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/home/);
  await expect(focused).toHaveText('Resume');
  await page.keyboard.press('ArrowDown');
  if (await focused.textContent() === 'View Continue Watching') await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await expect(focused).toHaveText(/^Cached top/);
  await page.waitForTimeout(2300);
  await expect(focused).toHaveCount(1);
  await page.keyboard.press('ArrowUp');
  await expect(focused).toHaveText(/^Resume /);
  await page.evaluate(() => localStorage.removeItem('flixtor-fixture:httpDelay'));
  for (const delay of [400, 0, 400, 0, 400]) {
    if (!delay) await page.evaluate(() => sessionStorage.setItem('flixtor-fixture:failBlur', 'true'));
    for (let rail = 0; rail < 4; rail++) { await page.keyboard.press('ArrowDown'); if (delay) await page.waitForTimeout(delay); }
    for (let rail = 0; rail < 4; rail++) { await page.keyboard.press('ArrowUp'); if (delay) await page.waitForTimeout(delay); }
    await page.waitForTimeout(500);
    await expect(focused).toHaveText(/^Resume /);
    const bounds = await focused.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.y).toBeGreaterThan(150);
    expect(bounds!.y + bounds!.height).toBeLessThan(1030);
    const screenshot = await page.screenshot({ path: info.outputPath(`continue-${delay}.png`) });
    const pixel = await page.evaluate(async ({ png, x, y }) => {
      const image = new Image(); image.src = `data:image/png;base64,${png}`; await image.decode();
      const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
      const context = canvas.getContext('2d')!; context.drawImage(image, 0, 0);
      return [...context.getImageData(x, y, 1, 1).data].slice(0, 3);
    }, { png: screenshot.toString('base64'), x: Math.round(bounds!.x + bounds!.width / 2), y: Math.round(bounds!.y + 100) });
    expect(pixel).toEqual([40, 90, 117]);
  }
  expect(errors).toEqual([]);
});
