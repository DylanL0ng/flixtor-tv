import { createHash } from 'node:crypto';
import { dehydrate, QueryClient } from '@tanstack/solid-query';
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
test('sort choices reorder shelves, preserve profile data and restore after browser navigation', async ({ page }) => {
  await home(page); await activate(page, 'Movies');
  await page.keyboard.press('ArrowUp');
  await activate(page, 'Latest'); await activate(page, 'A–Z'); await expect(page).toHaveURL(/sort=name/);
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
test('Home keeps its horizontal position across short rails and restores the focused title', async ({ page }) => {
  await home(page);
  const focused = page.locator('button[data-focused="true"]');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('Northbound');
  await page.keyboard.press('ArrowRight'); await expect(focused).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('The Test Series');
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('Quiet Harbour');
  await page.keyboard.press('ArrowLeft'); await expect(focused).toHaveText('Northbound');
  await page.keyboard.press('ArrowRight'); await expect(focused).toHaveText('Quiet Harbour');
  await page.keyboard.press('Enter'); await expect(page).toHaveURL(/detail/);
  await page.keyboard.press('Escape'); await expect(page).toHaveURL(/home/);
  await expect(focused).toHaveText('Quiet Harbour');
});
test('vertical navigation selects visible media without moving either rail horizontally', async ({ page }) => {
  const queries = new QueryClient();
  for (const [catalog, label] of [['top', 'Popular'], ['imdbRating', 'Featured']]) {
    queries.setQueryData(['discovery', 'movie', catalog], Array.from({ length: 18 }, (_, index) => ({
      id: `tt${catalog}${index}`, type: 'movie', name: `${label} ${index + 1}`, genres: [],
    })));
  }
  const snapshot = JSON.stringify({ version: 1, savedAt: Date.now(), state: dehydrate(queries) });
  queries.clear();
  await page.addInitScript(snapshot => localStorage.setItem('flixtor-fixture:querySnapshot', snapshot), snapshot);
  await home(page);
  const focused = page.locator('button[data-focused="true"]');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('Popular 1');
  for (let index = 2; index <= 11; index++) {
    await page.keyboard.press('ArrowRight'); await expect(focused).toHaveText(`Popular ${index}`);
  }
  // Let horizontal motion settle before measuring the row's on-screen slots.
  await page.waitForTimeout(400);
  const popular = page.getByRole('button', { name: 'Popular 11', exact: true, includeHidden: true });
  const featured = page.getByRole('button', { name: 'Featured 1', exact: true, includeHidden: true });
  const popularX = await popular.evaluate(node => parseFloat(node.style.left));
  const featuredX = await featured.evaluate(node => parseFloat(node.style.left));
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText(/^Featured [1-6]$/);
  await page.waitForTimeout(400);
  expect(await featured.evaluate(node => parseFloat(node.style.left))).toBeCloseTo(featuredX, 0);
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('Popular 11');
  await page.waitForTimeout(400);
  expect(await popular.evaluate(node => parseFloat(node.style.left))).toBeCloseTo(popularX, 0);
});
test('movie shelves keep full posters inside the viewport', async ({ page }) => {
  await home(page); await activate(page, 'Movies');
  const card = page.getByRole('button', { name: 'Northbound', exact: true }).first();
  await expect(card).toBeAttached();
  const bounds = await card.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.height).toBeGreaterThan(450);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(1080);
});
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
test('search keyboard wraps its row and View results provides an explicit handoff', async ({ page }) => {
  await home(page); await activate(page, 'Search');
  const field = page.getByRole('textbox', { name: 'Search films and TV shows' });
  await field.fill('north'); await expect(page).toHaveURL(/q=north/); await page.keyboard.press('ArrowDown');
  await expect(page.locator('button[data-focused="true"]')).toHaveText('A');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('B');
  await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('F');
  await page.keyboard.press('ArrowRight'); await expect(page.locator('button[data-focused="true"]')).toHaveText('A');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('F');
  await activate(page, 'View results'); await expect(page.locator('button[data-focused="true"]')).toHaveText('The Test Series');
  await page.keyboard.press('ArrowLeft'); await expect(page.locator('button[data-focused="true"]')).toHaveText('View results');
});
test('search keyboard keeps columns through short rows, actions, and symbol changes', async ({ page }) => {
  await home(page); await activate(page, 'Search');
  const focused = page.locator('button[data-focused="true"]');
  await page.getByRole('button', { name: 'E', exact: true }).focus();
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('View results');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('E');
  for (const key of ['K', 'Q', 'W', 'Z', '123', 'View results']) {
    await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText(key);
  }
  for (const key of ['123', 'Z', 'W', 'Q', 'K', 'E']) {
    await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText(key);
  }
  await page.getByRole('button', { name: '123', exact: true }).focus(); await page.keyboard.press('Enter'); await expect(focused).toHaveText('ABC');
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText(':');
  await page.keyboard.press('ArrowDown'); await expect(focused).toHaveText('ABC');
  await page.keyboard.press('Enter'); await expect(focused).toHaveText('123');
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('Z');
  await page.keyboard.press('Escape'); await expect(page.getByRole('textbox')).toBeFocused();
  await page.keyboard.press('ArrowUp'); await expect(focused).toHaveText('Movies & TV');
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
    await page.getByRole('button', { name: 'The Test Series', exact: true }).first().click();
    await page.getByRole('button', { name: '2. Chapter 2', exact: true }).click(); await expect(page).toHaveURL(/player/);
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await page.getByRole('button', { name: /settings, Subtitles/ }).click();
    await expect(page.getByRole('button', { name: /subtitles, Off/ })).toBeAttached();
  });
}
