import { expect, test, type Page } from '@playwright/test';

type Reply = { body: string; status?: number; delay?: number };
const empty = '<div class=contentList>No results</div>';
function cards(label: string, start = 1, count = 18) {
  return Array.from({ length: count }, (_, offset) => `<div class=cflip data-href="/watch/movie/${start + offset}/fixture"><div class=front><div class=card-footer>${label} ${start + offset}</div></div></div>`).join('');
}
function anime(label: string, count = 18) {
  return `<div class=film_list-wrap>${Array.from({ length: count }, (_, offset) => `<div class=flw-item><h3 class=film-name><a href=/fixture-${offset + 1}>${label} ${offset + 1}</a></h3><span class=fdi-item>TV</span></div>`).join('')}</div>`;
}
async function setup(page: Page, overrides: Record<string, Reply>) {
  await page.addInitScript(overrides => localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(overrides)), overrides);
  await page.goto('/');
  await page.getByRole('button', { name: 'My profile', exact: true }).focus(); await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/home/);
}
async function activate(page: Page, name: string) {
  await page.getByRole('button', { name, exact: true }).first().focus(); await page.keyboard.press('Enter');
}
const focused = (page: Page) => page.locator('button[data-focused="true"]');
for (const [destination, rightSteps] of [['Home', 5], ['Movies', 4], ['TV shows', 3], ['Anime', 2], ['My List', 1]] as const) {
  test(`Up from ${destination} reaches navigation and Search using only arrows`, async ({ page }) => {
    await setup(page, {});
    if (destination !== 'Home') await activate(page, destination);
    if (destination === 'Home') { await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText('Northbound'); }
    else await expect(focused(page)).toHaveText(destination === 'Anime' ? 'Anime fixture 1' : destination === 'My List' ? 'Browse movies' : 'The Test Series');
    await expect(page.getByRole('button', { name: 'Search anime', exact: true })).toHaveCount(0);
    for (let index = 0; index < 8 && await focused(page).textContent() !== destination; index++) await page.keyboard.press('ArrowUp');
    await expect(focused(page)).toHaveText(destination);
    for (let index = 0; index < rightSteps; index++) await page.keyboard.press('ArrowRight');
    await expect(focused(page)).toHaveText('Search');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/search/);
    await expect(page.getByRole('textbox', { name: 'Search films and TV shows', exact: true })).toBeAttached();
  });
}
async function requests(page: Page) {
  return page.evaluate(() => JSON.parse(sessionStorage.getItem('flixtor-fixture:requests') || '[]') as string[]);
}

for (const destination of ['Movies', 'TV shows', 'Anime']) {
  test(`${destination} uses independent rails, keeps horizontal position, and restores Details/Back`, async ({ page }) => {
    const kind = destination === 'Movies' ? 'movies' : 'tvshows';
    const overrides: Record<string, Reply> = destination === 'Anime' ? {
      '/most-popular': { body: anime('Popular') }, '/top-airing': { body: anime('Airing') }, '/recently-updated': { body: anime('Updated') },
    } : Object.fromEntries(['latest', 'best', 'name'].map(sort => [`/show/${kind}?ajax=1&page=1&sort=${sort}`, { body: cards(sort) }]));
    await setup(page, overrides); await activate(page, destination);
    const first = destination === 'Anime' ? 'Popular' : 'latest', second = destination === 'Anime' ? 'Airing' : 'best';
    await expect(focused(page)).toHaveText(`${first} 1`);
    for (let i = 2; i <= 11; i++) { await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText(`${first} ${i}`); }
    await page.waitForTimeout(300);
    const original = await focused(page).boundingBox();
    await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText(new RegExp(`^${second} [1-6]$`));
    await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText(`${first} 11`);
    await page.waitForTimeout(300);
    expect((await focused(page).boundingBox())!.x).toBeCloseTo(original!.x, 0);
    await page.keyboard.press('Enter'); await expect(page).toHaveURL(/detail/);
    await page.goBack(); await expect(focused(page)).toHaveText(`${first} 11`);
    await page.waitForTimeout(300);
    expect((await focused(page).boundingBox())!.x).toBeCloseTo(original!.x, 0);
  });
}

test('only the active shelf paginates; append preserves focus and repeated pages stop', async ({ page }) => {
  await setup(page, {
    '/show/movies?ajax=1&page=1&sort=latest': { body: cards('Latest', 1, 12) },
    '/show/movies?ajax=1&page=2&sort=latest': { body: cards('Latest', 13, 12), delay: 650 },
    '/show/movies?ajax=1&page=3&sort=latest': { body: cards('Latest', 13, 12) },
    '/show/movies?ajax=1&page=1&sort=best': { body: cards('Best', 101, 12) },
    '/show/movies?ajax=1&page=1&sort=name': { body: cards('Alphabetical', 201, 12) },
  });
  await activate(page, 'Movies'); await expect(focused(page)).toHaveText('Latest 1');
  for (let i = 2; i <= 9; i++) { await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText(`Latest ${i}`); }
  await expect.poll(async () => (await requests(page)).filter(r => r.includes('page=2&sort=latest')).length).toBe(1);
  await page.waitForTimeout(800); await expect(focused(page)).toHaveText('Latest 9');
  for (let i = 10; i <= 24; i++) { await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText(`Latest ${i}`); }
  await page.keyboard.press('ArrowRight'); await expect(focused(page)).toHaveText('Latest 24');
  const fetched = await requests(page);
  expect(fetched.some(r => /page=2&sort=(best|name)/.test(r))).toBe(false);
  expect(fetched.some(r => r.includes('page=4'))).toBe(false);
});

test('slow, empty, and failing shelves remain independent and retry is reachable', async ({ page }) => {
  await setup(page, {
    '/show/movies?ajax=1&page=1&sort=latest': { body: cards('Latest', 1, 3), delay: 800 },
    '/show/movies?ajax=1&page=1&sort=best': { body: 'Access denied', status: 403 },
    '/show/movies?ajax=1&page=1&sort=name': { body: empty },
  });
  await activate(page, 'Movies');
  // Empty state is derived from successful results, never from an in-flight query.
  await expect(page.getByRole('button', { name: 'Latest', exact: true })).toBeAttached();
  await expect(focused(page)).toHaveText('Latest 1');
  await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText('Retry Top rated');
  await page.evaluate(body => {
    const data = JSON.parse(localStorage.getItem('flixtor-fixture:httpOverrides')!);
    data['/show/movies?ajax=1&page=1&sort=best'] = { body };
    localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(data));
  }, cards('Best', 101, 3));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Best 101', exact: true, includeHidden: true })).toBeAttached();
  await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText(/^Latest /);
});

test('Anime URL search replaces categories and clearing it restores shelves through browser history', async ({ page }) => {
  await setup(page, { '/most-popular': { body: anime('Popular') }, '/top-airing': { body: anime('Airing') },
    '/recently-updated': { body: anime('Updated') }, '/search': { body: anime('Result') } });
  await activate(page, 'Anime'); await expect(focused(page)).toHaveText('Popular 1');
  await page.evaluate(() => { location.hash = '/anime?q=Naruto'; });
  await expect(focused(page)).toHaveText('Result 1');
  await expect(page.getByRole('button', { name: 'Airing 1', exact: true, includeHidden: true })).toHaveCount(0);
  await page.evaluate(() => { location.hash = '/anime'; });
  await expect(focused(page)).toHaveText('Popular 1');
  await page.goBack(); await expect(focused(page)).toHaveText('Result 1');
});
