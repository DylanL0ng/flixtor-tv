import { createHash } from 'node:crypto';
import { test, expect, type Page } from '@playwright/test';
async function activate(page: Page, name: string | RegExp) { const control = page.getByRole('button', { name }).first(); await control.focus(); await page.keyboard.press('Enter'); }
async function home(page: Page) { await page.goto('/'); await activate(page, 'My profile'); await expect(page).toHaveURL(/home/); }
async function openTestSeries(page: Page) { await activate(page, 'TV shows'); await activate(page, 'The Test Series'); }

test('remote profiles → detail → episode → player → settings → back', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await home(page); await openTestSeries(page); await expect(page).toHaveURL(/detail/);
  await activate(page, '1. Chapter 1'); await expect(page).toHaveURL(/player/);
  await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeAttached();
  await activate(page, 'Settings');
  await expect(page.getByRole('button', { name: /settings, Video quality/ })).toBeAttached();
  await page.keyboard.press('Escape'); await page.keyboard.press('Escape'); await expect(page).toHaveURL(/detail/);
  expect(errors).toEqual([]);
});
test('sort choices reset the grid, preserve profile data and restore after browser navigation', async ({ page }) => {
  await home(page); await activate(page, 'Movies'); await activate(page, 'Latest'); await activate(page, 'A–Z'); await expect(page).toHaveURL(/sort=name/);
  await activate(page, 'Northbound'); await expect(page).toHaveURL(/detail/); await page.goBack();
  await expect(page).toHaveURL(/sort=name/); await expect(page.locator('button[data-focused="true"]')).toHaveText('Northbound');
  await activate(page, 'My List'); await expect(page).toHaveURL(/list/); await page.reload();
  await expect(page.getByRole('button', { name: 'My profile', exact: true })).toBeAttached();
});
test('Home starts with discovery, D-pad moves through a distinct movie rail, and detail restores its origin', async ({ page }) => {
  await home(page); await expect(page.locator('button[data-focused="true"]')).toHaveText('Find on FlixTor');
  await page.keyboard.press('ArrowDown'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Northbound');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Northbound');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Northbound');
  await page.keyboard.press('ArrowUp'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Find on FlixTor');
  await page.keyboard.press('ArrowUp'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Home');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Movies');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Home');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter'); await expect(page).toHaveURL(/title=m%3A2/);
  await page.keyboard.press('Escape'); await expect(page).toHaveURL(/home/);
  await expect(page.locator('button[data-focused="true"]')).toHaveText('Northbound');
});
test('Home restores its saved discovery snapshot on launch', async ({ page }) => {
  await home(page); await expect(page.getByRole('button', { name: 'Northbound', exact: true })).toBeAttached();
  await page.waitForTimeout(800);
  expect(await page.evaluate(() => localStorage.getItem('flixtor-fixture:querySnapshot'))).toBeTruthy();
  await page.reload(); await activate(page, 'My profile');
  await expect(page.getByRole('button', { name: 'Northbound', exact: true })).toBeAttached();
});
test('movie grid keeps full posters inside the viewport', async ({ page }) => {
  await home(page); await activate(page, 'Movies');
  const card = page.getByRole('button', { name: 'Northbound', exact: true });
  await expect(card).toBeAttached();
  const bounds = await card.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.height).toBeGreaterThan(450);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(1080);
});
for (const [destination, first, below] of [
  ['Movies', 'The Test Series', 'First Light'],
  ['TV shows', 'The Test Series', 'First Light'],
  ['Anime', 'Anime fixture 1', 'Anime fixture 7'],
] as const) {
  test(`${destination} grid scrolls down one complete row`, async ({ page }) => {
    await home(page); await activate(page, destination);
    const focused = page.locator('button[data-focused="true"]');
    await expect(focused).toHaveText(first);
    await page.keyboard.press('ArrowDown');
    await expect(focused).toHaveText(below);
    const visiblePeer = page.getByRole('button', { name: destination === 'Anime' ? 'Anime fixture 8' : 'A New Chapter', exact: true });
    await expect(visiblePeer).toBeAttached();
    const bounds = await visiblePeer.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.height).toBeGreaterThan(350);
  });
}
test('confirmation traps focus and restores it after cancellation', async ({ page }) => {
  await home(page); await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Exit FlixTor TV?' })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeDisabled();
  await expect(page.locator('button[data-focused="true"]')).toHaveText('Stay');
  await page.keyboard.press('Tab'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Exit app');
  await page.keyboard.press('Tab'); await expect(page.locator('button[data-focused="true"]')).toHaveText('Stay');
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Home', exact: true })).toBeEnabled();
});
test('PIN pad rejects wrong digits, ignores held digits and unlocks with hardware input', async ({ page }) => {
  const hash = createHash('sha256').update('default:1234').digest('hex');
  await page.addInitScript(hash => {
    localStorage.setItem('flixtor-fixture:app', JSON.stringify({ schema: 1, profiles: [{ id: 'default', name: 'My profile', avatar: 0, quality: 0, autoplay: true, largerBuffer: false, locked: true }], libraries: { default: { favorites: [], viewing: [] } }, skip: {}, fit: { horizontal: 5, vertical: 5 } }));
    localStorage.setItem('flixtor-fixture:pin:default', hash);
  }, hash);
  await page.goto('/'); await activate(page, 'My profile');
  await expect(page.getByRole('dialog', { name: 'Unlock My profile' })).toBeAttached();
  await page.keyboard.type('1111'); await expect(page.locator('#status')).toContainText('Incorrect PIN');
  await page.keyboard.down('1'); await page.keyboard.down('1'); await page.keyboard.up('1'); await page.keyboard.type('234');
  await expect(page).toHaveURL(/home/); await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('profile text entry uses the shared keyboard and persists the name', async ({ page }) => {
  await page.goto('/'); await activate(page, 'Add profile'); await expect(page.getByRole('dialog', { name: 'Profile name' })).toBeAttached();
  await page.getByRole('textbox', { name: 'Profile name', exact: true }).fill('Alice'); await activate(page, 'Save');
  await expect(page.getByRole('button', { name: 'Alice', exact: true })).toBeAttached();
  await page.reload(); await expect(page.getByRole('button', { name: 'Alice', exact: true })).toBeAttached();
});
test('search preserves text focus through refresh and supports direct hardware typing', async ({ page }) => {
  await home(page); await activate(page, 'Search');
  const field = page.getByRole('textbox', { name: 'Search films and TV shows' });
  await field.fill('north'); await expect(page).toHaveURL(/q=north/); await expect(field).toBeFocused();
  await field.fill('quiet'); await expect(page).toHaveURL(/q=quiet/); await expect(field).toBeFocused();
  await page.keyboard.press('ArrowDown'); await page.keyboard.type('abc'); await expect(field).toHaveValue('quietabc');
  await expect(page).toHaveURL(/q=quietabc/);
});
test('search keyboard traverses its row before handing off and restores the last key', async ({ page }) => {
  await home(page); await activate(page, 'Search');
  const field = page.getByRole('textbox', { name: 'Search films and TV shows' });
  await field.fill('north'); await expect(page).toHaveURL(/q=north/); await page.keyboard.press('ArrowDown');
  await expect(page.locator('button[data-focused="true"]')).toHaveText('A');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('B');
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('F');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('The Test Series');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('F');
});
test('screen-fit Cancel discards draft and Save persists it', async ({ page }) => {
  await home(page); await activate(page, 'Settings'); await activate(page, 'Display'); await activate(page, /Display, Adjust screen fit/);
  await activate(page, 'Increase horizontal'); await activate(page, 'Cancel');
  await expect(page.getByRole('button', { name: /Display, Adjust screen fit, 5% \/ 5%/ })).toBeAttached();
  await activate(page, /Display, Adjust screen fit/); await activate(page, 'Increase horizontal'); await activate(page, 'Save screen fit');
  await expect(page.getByRole('button', { name: /Display, Adjust screen fit, 6% \/ 5%/ })).toBeAttached();
});
for (const viewport of [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }]) {
  test(`pointer targets match animated controls at ${viewport.height}p`, async ({ page }) => {
    await page.setViewportSize(viewport); await page.goto('/');
    await page.getByRole('button', { name: 'My profile', exact: true }).click();
    await page.getByRole('button', { name: 'TV shows', exact: true }).click();
    await page.getByRole('button', { name: 'The Test Series', exact: true }).click();
    await page.getByRole('button', { name: '2. Chapter 2', exact: true }).click(); await expect(page).toHaveURL(/player/);
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await page.getByRole('button', { name: /settings, Subtitles/ }).click();
    await expect(page.getByRole('button', { name: /subtitles, Off/ })).toBeAttached();
  });
}
