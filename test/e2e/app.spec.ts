import { test, expect, type Page } from '@playwright/test';
async function activate(page: Page, name: string | RegExp) { const control = page.getByRole('button', { name }).first(); await control.focus(); await page.keyboard.press('Enter'); }
test('remote profiles → catalogue → episode → player → settings → back', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await activate(page, 'My profile');
  await expect(page).toHaveURL(/home/);
  await activate(page, 'The Test Series');
  await expect(page).toHaveURL(/detail/);
  await activate(page, /Episodes, 1\. Chapter 1/);
  await expect(page).toHaveURL(/player/);
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeAttached();
  await activate(page, 'Settings');
  await expect(page.getByRole('button', { name: /settings, Video quality/ })).toBeAttached();
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(/detail/);
  expect(errors).toEqual([]);
});
test('profile persistence and catalogue reset', async ({ page }) => {
  await page.goto('/'); await activate(page, 'My profile');
  await activate(page, 'Movies'); await expect(page).toHaveURL(/movies/);
  await activate(page, 'A–Z'); await expect(page).toHaveURL(/sort=name/);
  await activate(page, 'My List'); await expect(page).toHaveURL(/list/);
  await page.reload(); await expect(page.getByRole('button', { name: 'My profile', exact: true })).toBeAttached();
});

test('arrows reach posters, activate a different show and restore focus on return', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('button[data-focused="true"]')).toHaveText('My profile');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/home/);
  await expect(page.getByRole('button', { name: 'The Test Series', exact: true })).toBeAttached();
  const focused = page.locator('button[data-focused="true"]');
  await expect(focused).toHaveText('Search');
  await page.keyboard.press('ArrowDown');
  await expect(focused).toHaveText('The Test Series');
  await page.keyboard.press('ArrowRight');
  await expect(focused).toHaveText('Northbound');
  await page.keyboard.press('Enter'); await expect(page).toHaveURL(/title=m%3A2/);
  await page.keyboard.press('Escape'); await expect(page).toHaveURL(/home/);
  await expect(focused).toHaveText('Northbound');
});

for (const viewport of [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }]) {
  test(`cursor selects posters and a non-default menu row at ${viewport.height}p`, async ({ page }) => {
    page.on('console', msg => console.log('PAGE LOG:', msg.text()));
    await page.setViewportSize(viewport); await page.goto('/');
    async function click(name: string | RegExp) {
      const button = page.getByRole('button', { name }).first();
      await expect(button).toBeAttached();
      await expect.poll(async () => {
        const b1 = await button.boundingBox();
        await new Promise(r => setTimeout(r, 60));
        const b2 = await button.boundingBox();
        return b1 && b2 && b1.width > 0 && Math.abs(b1.x - b2.x) < 2 && Math.abs(b1.y - b2.y) < 2;
      }).toBe(true);
      const box = (await button.boundingBox())!;
      const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
      const el = await page.evaluate(p => document.elementFromPoint(p.x, p.y)?.outerHTML?.slice(0, 80), point);
      console.log('CLICKING:', name, 'point:', point, 'element:', el);
      await page.mouse.move(point.x, point.y);
      await page.mouse.click(point.x, point.y);
    }
    await click('My profile'); await expect(page).toHaveURL(/home/);
    await click('The Test Series'); await expect(page).toHaveURL(/detail/);
    await click(/Episodes, 2\. Chapter 2/); await expect(page).toHaveURL(/player/);
    await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeAttached();
    await click('Settings');
    await click(/settings, Subtitles/);
    await expect(page.getByRole('button', { name: /subtitles, Off/ })).toBeAttached();
    await expect(page.locator('button[data-focused="true"]')).toContainText('subtitles');
  });
}
