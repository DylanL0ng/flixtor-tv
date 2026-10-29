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
async function override(page: Page, path: string, reply: Reply) {
  await page.evaluate(({ path, reply }) => {
    const data = JSON.parse(localStorage.getItem('flixtor-fixture:httpOverrides') || '{}');
    data[path] = reply; localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(data));
  }, { path, reply });
}
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

test('search switches providers atomically, restores Anime details, and follows mode history', async ({ page }, info) => {
  await setup(page, {}); await activate(page, 'Search');
  const field = page.getByRole('textbox');
  await field.fill('Naruto');
  await page.getByRole('button', { name: 'Anime', exact: true }).last().focus(); await page.keyboard.press('Enter');
  await expect(field).toHaveAttribute('aria-label', 'Search anime');
  await expect(field).toHaveValue('Naruto');
  await expect(page).toHaveURL(/mode=anime/);
  await expect.poll(async () => (await requests(page)).some(url => url.includes('/search?page=1&keyword=Naruto'))).toBe(true);
  await expect(page.getByRole('button', { name: 'Anime fixture 1', exact: true })).toBeAttached();
  await page.screenshot({ path: info.outputPath('search-anime.png') });
  await activate(page, 'Anime fixture 1'); await expect(page).toHaveURL(/detail/);
  await page.goBack(); await expect(page).toHaveURL(/mode=anime/);
  await expect(focused(page)).toHaveText('Anime fixture 1');
  await activate(page, 'Movies & TV'); await expect(field).toHaveValue('Naruto');
  await expect(field).toHaveAttribute('aria-label', 'Search films and TV shows');
  await expect(page.getByRole('button', { name: 'The Test Series', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Anime fixture 1', exact: true })).toHaveCount(0);
  await page.goBack(); await expect(page).toHaveURL(/mode=anime/);
  await expect(field).toHaveAttribute('aria-label', 'Search anime');
  await page.goForward(); await expect(field).toHaveAttribute('aria-label', 'Search films and TV shows');
});

test('anime search cancels obsolete results and handles empty, error, retry, and clearing', async ({ page }) => {
  await setup(page, {
    '/search?page=1&keyword=slow': { body: anime('Obsolete'), delay: 1000 },
    '/search?page=1&keyword=fast': { body: anime('Current') },
    '/search?page=1&keyword=jjk': { body: '<div id=main-content><div class="block_area-content film_list film_list-grid"><p>No animes found.</p></div></div>' },
    '/search?page=1&keyword=broken': { body: '', status: 503 },
  });
  await activate(page, 'Search');
  await page.getByRole('button', { name: 'Anime', exact: true }).last().focus(); await page.keyboard.press('Enter');
  const field = page.getByRole('textbox', { name: 'Search anime', exact: true });
  await field.fill('slow'); await expect(page).toHaveURL(/q=slow/);
  await field.fill('fast'); await expect(page.getByRole('button', { name: 'Current 1', exact: true })).toBeAttached();
  await page.waitForTimeout(1100);
  await expect(page.getByRole('button', { name: 'Obsolete 1', exact: true })).toHaveCount(0);
  await field.fill('jjk'); await expect(page.locator('#status')).toContainText('No results');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await field.fill('broken'); await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeAttached();
  await page.evaluate(() => localStorage.setItem('flixtor-fixture:httpOverrides', '{}'));
  await activate(page, 'Try again'); await expect(page.getByRole('button', { name: 'Anime fixture 1', exact: true })).toBeAttached();
  await field.fill(''); await expect(page.locator('#status')).toContainText('Search for anime.');
  await expect(page.getByRole('button', { name: 'Anime fixture 1', exact: true })).toHaveCount(0);
});

for (const height of [1080, 720]) {
  test(`search heading stays above the input and scrolling results at ${height}p`, async ({ page }, info) => {
    await page.setViewportSize({ width: height * 16 / 9, height });
    await setup(page, { '/search': { body: anime('Jujutsu Kaisen long anime title', 30) } });
    await activate(page, 'Search');
    await page.getByRole('button', { name: 'Anime', exact: true }).last().focus(); await page.keyboard.press('Enter');
    const field = page.getByRole('textbox', { name: 'Search anime' });
    await field.fill('jujutsu');
    await page.getByRole('button', { name: 'Jujutsu Kaisen long anime title 1', exact: true }).focus();
    for (const index of [5, 9, 13]) {
      await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText(`Jujutsu Kaisen long anime title ${index}`);
    }
    await expect.poll(async () => {
      const input = await field.boundingBox(), poster = await focused(page).boundingBox();
      return !!input && !!poster && poster.y >= input.y + input.height;
    }).toBe(true);
    const heading = await page.evaluate(async () => {
      const path = '/src/ui/focus.ts'; const { activeElement, nodeRect } = await import(path);
      let root = activeElement(); while (root?.parent) root = root.parent;
      const visit = (node: typeof root): ReturnType<typeof nodeRect> | undefined => {
        if (!node?.lng) return;
        if (node.lng.text === 'Find your next story.') return nodeRect(node);
        for (const child of node.children || []) { const result = visit(child); if (result) return result; }
      };
      return visit(root);
    });
    expect(heading?.visible).toBe(true);
    expect(heading!.y + heading!.height).toBeLessThan((await field.boundingBox())!.y);
    await page.screenshot({ path: info.outputPath('search-scrolled.png') });
  });

  test(`library grids scroll their heading with media and restore focus at ${height}p`, async ({ page }, info) => {
    await page.setViewportSize({ width: height * 16 / 9, height });
    await page.addInitScript(() => {
      const titles = Array.from({ length: 30 }, (_, index) => ({ id: String(index + 1), source: 'FLIXTOR', type: 'MOVIE',
        path: `/watch/movie/${index + 1}/fixture`, name: `Library title ${index + 1}`, year: '2026' }));
      localStorage.setItem('flixtor-fixture:app', JSON.stringify({ schema: 1,
        profiles: [{ id: 'default', name: 'My profile', avatar: 0, quality: 0, autoplay: true, largerBuffer: false, locked: false }],
        libraries: { default: { favorites: titles, viewing: titles.map(title => ({ title, position: 30_000, duration: 120_000 })) } },
        skip: {}, fit: { horizontal: 10, vertical: 10 } }));
    });
    await setup(page, {});
    for (const route of ['list', 'continue']) {
      await page.evaluate(route => { location.hash = '/' + route; }, route);
      await expect(focused(page)).toHaveText('Library title 1');
      await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText('Library title 7');
      await page.keyboard.press('ArrowDown'); await expect(focused(page)).toHaveText('Library title 13');
      await page.screenshot({ path: info.outputPath(`${route}-grid-scrolled.png`) });
      const poster = await focused(page).boundingBox(), navigation = await page.getByRole('button', { name: 'Home', exact: true }).boundingBox();
      expect(poster!.y).toBeGreaterThan(navigation!.y + navigation!.height);
      await page.keyboard.press('Enter'); await expect(page).toHaveURL(/detail/);
      await page.goBack(); await expect(focused(page)).toHaveText('Library title 13');
      await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText('Library title 7');
      await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText('Library title 1');
      await page.keyboard.press('ArrowUp'); await expect(focused(page)).toHaveText(route === 'list' ? 'My List' : 'Home');
    }
  });

  test(`media headings scroll with shelves without colliding at ${height}p`, async ({ page }, info) => {
    await page.setViewportSize({ width: height * 16 / 9, height });
    await setup(page, {});
    const headingBounds = (label: string) => page.evaluate(async label => {
      const path = '/src/ui/focus.ts';
      const { activeElement, nodeRect } = await import(path);
      let root = activeElement(); while (root?.parent) root = root.parent;
      const visit = (node: typeof root): ReturnType<typeof nodeRect> | undefined => {
        if (!node?.lng) return;
        if (node.lng.text === label && node.lng.fontSize >= 36) return nodeRect(node);
        for (const child of node.children || []) { const found = visit(child); if (found) return found; }
      };
      return visit(root);
    }, label);
    for (const destination of ['Movies', 'TV shows', 'Anime']) {
      await activate(page, destination);
      if (destination !== 'Anime') {
        await page.keyboard.press('ArrowUp');
        await expect.poll(async () => (await headingBounds(destination))?.visible).toBe(true);
        const heading = (await headingBounds(destination))!, shelf = (await headingBounds('Latest'))!;
        expect(heading.y + heading.height).toBeLessThan(shelf.y);
        await page.screenshot({ path: info.outputPath(`${destination}-heading.png`) });
        await page.keyboard.press('ArrowDown');
      }
      await page.keyboard.press('ArrowDown');
      await expect.poll(async () => (await headingBounds(destination))?.visible).toBe(false);
      await page.screenshot({ path: info.outputPath(`${destination}-scrolled.png`) });
      const poster = await focused(page).boundingBox(), navigation = await page.getByRole('button', { name: destination, exact: true }).boundingBox();
      expect(poster!.y).toBeGreaterThan(navigation!.y + navigation!.height);
    }
  });
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
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(focused(page)).toHaveText('Try again');
  await page.evaluate(body => {
    const data = JSON.parse(localStorage.getItem('flixtor-fixture:httpOverrides')!);
    data['/show/movies?ajax=1&page=1&sort=best'] = { body };
    localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(data));
  }, cards('Best', 101, 3));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Best 101', exact: true, includeHidden: true })).toBeAttached();
  expect((await requests(page)).filter(path => path === '/show/movies?ajax=1&page=1&sort=latest')).toHaveLength(1);
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

test('one dialog retries concurrent failures and remains open after unsuccessful retry', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  const best = '/show/movies?ajax=1&page=1&sort=best', name = '/show/movies?ajax=1&page=1&sort=name';
  await setup(page, { [best]: { body: 'Access denied', status: 403 }, [name]: { body: 'Unavailable', status: 503, delay: 300 } });
  await activate(page, 'Movies');
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect.poll(async () => (await requests(page)).filter(path => path === name).length).toBe(1);
  await activate(page, 'Try again');
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeEnabled();
  await override(page, best, { body: cards('Best', 101, 3), delay: 500 });
  await override(page, name, { body: cards('Name', 201, 3), delay: 500 });
  await activate(page, 'Try again');
  await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeDisabled();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Best 101', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Name 201', exact: true, includeHidden: true })).toBeAttached();
  expect((await requests(page)).filter(path => path === '/show/movies?ajax=1&page=1&sort=latest')).toHaveLength(1);
  expect(errors).toEqual([]);
});

test('dismissal suppresses late failures, permits reopening, and resets across navigation', async ({ page }) => {
  await setup(page, {
    '/show/movies?ajax=1&page=1&sort=best': { body: 'Access denied', status: 403 },
    '/show/movies?ajax=1&page=1&sort=name': { body: 'Unavailable', status: 503, delay: 1000 },
  });
  await activate(page, 'Movies'); await expect(page.getByRole('dialog')).toHaveCount(1);
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Retry loading', exact: true })).toBeAttached();
  await page.waitForTimeout(1200);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await activate(page, 'Retry loading'); await expect(page.getByRole('dialog')).toHaveCount(1);
  await activate(page, 'Close');
  await activate(page, 'TV shows'); await page.goBack();
  await expect(page.getByRole('dialog')).toHaveCount(1);
});

test('capacity dialog opens account settings and offers both sign-in methods', async ({ page }) => {
  await setup(page, { '/show/movies?ajax=1&page=1&sort=best': {
    body: '<div id="content">All our servers are full. We cannot accept additional users.</div>',
  } });
  await activate(page, 'Movies'); await expect(page.getByRole('dialog')).toHaveCount(1);
  await expect(focused(page)).toHaveText('Sign in');
  await page.keyboard.press('Enter'); await expect(page).toHaveURL(/settings\/account/);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Account, Sign in with email', exact: true })).toBeAttached();
  await expect(page.getByRole('button', { name: 'Account, Sign in with phone', exact: true })).toBeAttached();
});

test('search failure uses one dialog and ignores typing behind modal', async ({ page }) => {
  await setup(page, { '/search/test': { body: 'Access denied', status: 403 } });
  await page.evaluate(() => { location.hash = '/search?q=test'; });
  await expect(page.getByRole('dialog')).toHaveCount(1);
  await page.keyboard.type('hidden'); await expect(page).toHaveURL(/q=test$/);
  await override(page, '/search/test', { body: cards('Found', 501, 3) });
  await activate(page, 'Try again'); await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Found 501', exact: true })).toBeAttached();
});

test('details failure retries in dialog at 720p with screen-fit margins', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  const detailPath = '/watch/tv/1/fixture';
  await setup(page, { [detailPath]: { body: 'Access denied', status: 403 } });
  await activate(page, 'TV shows'); await activate(page, 'The Test Series');
  await expect(page.getByRole('dialog')).toHaveCount(1);
  for (const button of await page.getByRole('dialog').getByRole('button').all()) {
    const box = await button.boundingBox(); expect(box).toBeTruthy();
    expect(box!.x).toBeGreaterThanOrEqual(0); expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(1280); expect(box!.y + box!.height).toBeLessThanOrEqual(720);
  }
  await page.evaluate(path => {
    const data = JSON.parse(localStorage.getItem('flixtor-fixture:httpOverrides')!); delete data[path!];
    localStorage.setItem('flixtor-fixture:httpOverrides', JSON.stringify(data));
  }, detailPath);
  await activate(page, 'Try again'); await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeAttached();
});
