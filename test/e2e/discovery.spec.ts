import { expect, test, type Page } from '@playwright/test';
const focused = (page: Page) => page.locator('button[data-focused="true"]');
async function activate(page: Page, name: string) {
  for (let i = 0; i < 12 && !await page.getByRole('button', { name, exact: true }).count() && await page.getByRole('button', { name, exact: true, includeHidden: true }).count(); i++) await page.keyboard.press('ArrowDown');
  const control = page.getByRole('button', { name, exact: true }).first(); await control.focus(); await page.keyboard.press('Enter'); }
async function home(page: Page) { await page.addInitScript(() => localStorage.setItem('flixtor-fixture:tmdb', 'true')); await page.goto('/'); await activate(page, 'My profile'); await expect(page).toHaveURL(/home/); }
test('movie details → cast/director/studio → movies → back preserves discovery', async ({ page }, info) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await home(page); await activate(page, 'Northbound');
  await expect(page).toHaveURL(/detail/); await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeAttached();
  await page.screenshot({ path: info.outputPath('movie-details.png') });
  await expect(page.getByRole('button', { name: 'Alex Example', exact: true, includeHidden: true })).toBeAttached();
  await activate(page, 'Sam Director'); await expect(page).toHaveURL(/kind=director/);
  await expect(page.getByRole('button', { name: 'Northbound', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Quiet Harbour', exact: true })).toHaveCount(0);
  await page.goBack(); await activate(page, 'Example Pictures'); await expect(page).toHaveURL(/kind=studio/);
  await activate(page, 'Quiet Harbour'); await expect(page).toHaveURL(/tmdb=3/);
  await expect(page.getByRole('button', { name: 'Alex Example', exact: true, includeHidden: true })).toBeAttached();
  await page.goBack(); await expect(focused(page)).toHaveText('Quiet Harbour');
  expect(errors).toEqual([]);
});
test('metadata failure stays local to discovery on an existing playable movie', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify({ '/3/movie/2': { status: 503, body: '{}' } })));
  await home(page); await activate(page, 'Movies'); await activate(page, 'Northbound');
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Retry discovery', exact: true, includeHidden: true })).toBeAttached();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('slow metadata does not suspend playable details', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify({ '/3/movie/2': { delay: 2500, body: '{}' } })));
  await home(page); await activate(page, 'Movies'); await activate(page, 'Northbound');
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeAttached({ timeout: 1500 });
  await expect(page.getByRole('button', { name: 'Back', exact: true })).toBeAttached();
});
for (const height of [720, 1080]) test(`TMDB attribution remains readable at ${height}p`, async ({ page }, info) => {
  await page.setViewportSize({ width: height * 16 / 9, height });
  await home(page); await activate(page, 'Settings'); await activate(page, 'About');
  await expect(page.getByRole('button', { name: 'Back to settings', exact: true })).toBeAttached();
  const box = await focused(page).boundingBox(); expect(box!.y + box!.height).toBeLessThan(height);
  await page.screenshot({ path: info.outputPath('about.png') });
});
test('unavailable movies still expose cast and studios without Play', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify({ '/search/Northbound': { body: '<div class="contentList">No results</div>' } })));
  await home(page); await activate(page, 'Northbound');
  await expect(page.getByRole('button', { name: 'Alex Example', exact: true, includeHidden: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toHaveCount(0);
  await activate(page, 'Alex Example'); await expect(page).toHaveURL(/kind=actor/);
});
test('cast selection searches FlixTor actor credits across movies and TV, and restores results on Back', async ({ page }) => {
  await home(page); await activate(page, 'Northbound'); await activate(page, 'Alex Example');
  await expect(page).toHaveURL(/kind=actor/);
  await expect(page.getByRole('button', { name: 'The Test Series', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Northbound', exact: true })).toBeAttached();
  const requests = await page.evaluate(() => JSON.parse(sessionStorage.getItem('flixtor-fixture:requests') || '[]') as string[]);
  const actorRequest = requests.find(path => decodeURIComponent(path).startsWith('/search/@actor: Alex Example'));
  expect(actorRequest).toBeTruthy(); expect(new URL(actorRequest!, 'https://flixtor.to').searchParams.get('tab')).toBe('movies,tvshows');
  expect(requests.some(path => path.includes('/person/101/movie_credits'))).toBe(false);
  await activate(page, 'The Test Series'); await expect(page).toHaveURL(/title=t%3A1/);
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeAttached();
  await page.goBack(); await expect(focused(page)).toHaveText('The Test Series');
});
test('pending movie availability cannot hide actor results after leaving Details', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify({ '/search/Northbound': { delay: 3500, body: '<div class="contentList">No results</div>' } })));
  await home(page); await activate(page, 'Northbound'); await activate(page, 'Alex Example');
  await expect(page).toHaveURL(/kind=actor/);
  await expect(focused(page)).toHaveText('The Test Series');
  await expect(focused(page)).toBeVisible();
  await page.waitForTimeout(3700);
  await expect(focused(page)).toHaveText('The Test Series');
  await expect(focused(page)).toBeVisible();
});
test('people/studio search and voice dictate current mode; stale results are ignored', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:voice', JSON.stringify({ text: 'Alex Example' })));
  await home(page); await activate(page, 'Search'); await activate(page, 'People'); await activate(page, 'Voice search');
  await expect(page).toHaveURL(/mode=people.*q=Alex|q=Alex.*mode=people/);
  await expect(page.getByRole('button', { name: 'Alex Example', exact: true, includeHidden: true })).toBeAttached();
  await activate(page, 'Directed by Alex Example'); await expect(page).toHaveURL(/kind=director/);
  await page.goBack(); await activate(page, 'Studios');
  await expect(page.getByRole('button', { name: 'Example Pictures', exact: true })).toBeAttached();
  await page.evaluate(() => localStorage.setItem('flixtor-fixture:voice', JSON.stringify({ text: 'stale transcript', delay: 700 })));
  await activate(page, 'Voice search'); await page.getByRole('button', { name: 'Anime', exact: true }).last().focus(); await page.keyboard.press('Enter');
  await page.waitForTimeout(850); await expect(page).not.toHaveURL(/stale/); await expect(page).toHaveURL(/mode=anime/);
});
test('voice cancellation preserves query and draft', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:voice', JSON.stringify({ text: null })));
  await home(page); await activate(page, 'Search'); await page.keyboard.type('Northbound');
  await expect(page).toHaveURL(/q=Northbound/); await activate(page, 'Voice search');
  await expect(focused(page)).toHaveText('Voice search'); await expect(page).toHaveURL(/q=Northbound/);
});
test('Popular Movies retains usable focus when delayed TMDB replaces cached discovery', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify({
    '/3/trending/movie/week': { delay: 3500, body: JSON.stringify({ results: Array.from({ length: 18 }, (_, i) => ({ id: 300 + i, title: `Fresh movie ${i + 1}`, release_date: '2026-01-01' })), total_pages: 1 }) },
  })));
  await home(page);
  await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText('Northbound');
  await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText('Quiet Harbour');
  await page.waitForTimeout(3800);
  await expect(focused(page)).toHaveCount(1); await expect(focused(page)).toBeVisible();
  await expect(focused(page)).toHaveText('Fresh movie 2');
  await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText('Fresh movie 3');
  await page.keyboard.press('ArrowDown'); await page.keyboard.press('ArrowUp');
  await expect(focused(page)).toHaveText('Fresh movie 3');
  expect(errors).toEqual([]);
});
test('View all uses server sorting and remote Up reaches the sort control', async ({ page }) => {
  await home(page); await activate(page, 'Movies'); await activate(page, 'View all Latest');
  await expect(page).toHaveURL(/shelf=latest/);
  await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText('Latest');
  await page.keyboard.press('Enter'); await activate(page, 'Sort titles, A–Z');
  await expect(page).toHaveURL(/shelf=name/); await expect(focused(page)).toHaveText('A New Chapter');
});
for (const height of [720, 1080, 2160]) test(`rapid rail navigation stays inside screen-fit bounds at ${height}p`, async ({ page }) => {
  await page.setViewportSize({ width: height * 16 / 9, height });
  await page.addInitScript(() => {
    const cards = Array.from({ length: 60 }, (_, i) => `<div class="cflip" data-href="/watch/movie/${i + 100}/fixture"><div class="front"><div class="card-footer">Movie ${i + 1}</div><div class="card-badge top"><span class="badge">2026</span></div></div></div>`).join('');
    localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(Object.fromEntries(['latest', 'best', 'name'].map(sort => [`/show/movies?ajax=1&page=1&sort=${sort}`, { body: cards }]))));
  });
  await home(page); await activate(page, 'Movies');
  await expect(focused(page)).toHaveText('Movie 1');
  for (let i = 0; i < 30; i++) await page.keyboard.down('ArrowRight');
  await page.keyboard.up('ArrowRight'); await expect(focused(page)).toHaveText('Movie 31');
  await expect.poll(async () => page.evaluate(async () => {
    const path = '/src/ui/focus.ts'; const { activeElement, nodeRect } = await import(path);
    const rect = nodeRect(activeElement(), false);
    return rect.x >= innerWidth * 0.05 && rect.x + rect.width <= innerWidth * 0.95;
  })).toBe(true);
  for (let i = 0; i < 25; i++) await page.keyboard.down('ArrowLeft');
  await page.keyboard.up('ArrowLeft'); await expect(focused(page)).toHaveText('Movie 6');
  await page.keyboard.press('Enter'); await page.goBack(); await expect(focused(page)).toHaveText('Movie 6');
});
