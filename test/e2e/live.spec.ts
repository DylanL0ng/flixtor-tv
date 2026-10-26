import { expect, test } from '@playwright/test';

test.use({ launchOptions: { args: ['--autoplay-policy=no-user-gesture-required'] } });

test('normal browser mode loads catalogue and artwork through the live bridge', async ({ page }) => {
  const calls: { method: string; args: Record<string, string> }[] = [];
  const card = '<div class=contentListM><div class=cflip data-href="/watch/movie/700/live"><div class=front><img src="https://images.example/poster.jpg"><div class=card-footer>Bridge catalogue</div></div></div></div>';
  await page.route('**/__platform/call', async route => {
    const { method, args } = route.request().postDataJSON(); calls.push({ method, args });
    let value: unknown = true;
    if (method === 'http') value = { status: 200, location: '', body: '<meta name=csrf-token content=live-token>' + card };
    if (method === 'art') value = '/live-poster.png';
    await route.fulfill({ json: { value } });
  });
  await page.route('**/live-poster.png', route => route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=', 'base64') }));
  await page.goto('/');
  const profile = page.getByRole('button', { name: 'My profile', exact: true }); await profile.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Bridge catalogue', exact: true }).first()).toBeAttached();
  await expect.poll(() => calls.some(call => call.method === 'art')).toBe(true);
  expect(calls.some(call => call.method === 'session.open')).toBe(true);
  expect(calls.some(call => call.method === 'http' && call.args.url === 'https://flixtor.to/home')).toBe(true);
  expect(await page.getByRole('button', { name: 'The Test Series', exact: true }).count()).toBe(0);
});

test('browser playback uses real video time, pause, resume and teardown', async ({ page }) => {
  await page.route('**/__platform/call', async route => {
    const { method } = route.request().postDataJSON();
    await route.fulfill({ json: { value: method === 'media' ? { uri: '/test/fixtures/browser-player.webm', captions: [] } : true } });
  });
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const moduleUrl = '/src/platform/browser.ts';
    const { browserPlatform } = await import(moduleUrl);
    const platform = browserPlatform();
    try {
      await platform.call('player.load', { id: 'real-video', uri: 'https://media.example/video.webm', mime: 'video/webm', position: 500, play: true });
      const video = document.querySelector('video')!;
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Real video did not play')), 8000);
        if (!video.paused && video.readyState >= 3) { clearTimeout(timeout); resolve(); }
        else video.addEventListener('playing', () => { clearTimeout(timeout); resolve(); }, { once: true });
      });
      await platform.call('player.command', { id: 'real-video', action: 'pause' });
      const paused = video.currentTime;
      await new Promise(resolve => setTimeout(resolve, 150));
      const held = Math.abs(video.currentTime - paused) < 0.05;
      await platform.call('player.command', { id: 'real-video', action: 'play' });
      await new Promise(resolve => setTimeout(resolve, 300));
      const advanced = video.currentTime > paused + 0.1;
      await platform.call('player.command', { id: 'real-video', action: 'stop' });
      return { held, advanced, resumedAt: paused, released: !document.querySelector('video') };
    } finally { platform.dispose(); }
  });
  expect(result).toMatchObject({ held: true, advanced: true, released: true });
  expect(result.resumedAt).toBeGreaterThanOrEqual(0.45);
});

test('live browser password entry is masked and cancellable', async ({ page }) => {
  await page.route('**/__platform/call', route => route.fulfill({ json: { value: true } }));
  await page.goto('/');
  const result = page.evaluate(async () => {
    const moduleUrl = '/src/platform/browser.ts'; const { browserPlatform } = await import(moduleUrl); const platform = browserPlatform();
    try { return await platform.call('input', { label: 'Account password', secret: true }); } finally { platform.dispose(); }
  });
  const field = page.locator('dialog input[type="password"]');
  await expect(field).toHaveAttribute('type', 'password'); await field.fill('test-password');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  expect(await result).toBe('test-password'); await expect(page.locator('dialog')).toHaveCount(0);
  const cancelled = page.evaluate(async () => {
    const moduleUrl = '/src/platform/browser.ts'; const { browserPlatform } = await import(moduleUrl); const platform = browserPlatform();
    try { return await platform.call('input', { label: 'Account password', secret: true }); } finally { platform.dispose(); }
  });
  await expect(page.locator('dialog input[type="password"]')).toBeVisible(); await page.keyboard.press('Escape');
  expect(await cancelled).toBeNull();
});

test('search cancels stale responses and distinguishes slow, empty and failed results', async ({ page }) => {
  const card = (name: string, id: string) => `<div class=contentListM><div class=cflip data-href="/watch/movie/${id}/fixture"><div class=front><div class=card-footer>${name}</div></div></div></div>`;
  await page.route('**/__platform/call', async route => {
    const { method, args } = route.request().postDataJSON();
    if (method !== 'http') { await route.fulfill({ json: { value: true } }); return; }
    const path = new URL(args.url).pathname;
    if (path.includes('/search/slow')) await new Promise(resolve => setTimeout(resolve, 900));
    const body = '<meta name=csrf-token content=qa-token>' + (path.includes('/search/empty') ? '<div class=contentList>No results</div>' : card(path.includes('/search/slow') ? 'Obsolete result' : 'Current result', '701'));
    try { await route.fulfill({ json: { value: { status: path.includes('/search/broken') ? 503 : 200, location: '', body } } }); } catch { /* The app cancelled an obsolete request. */ }
  });
  await page.goto('/'); await page.getByRole('button', { name: 'My profile', exact: true }).click();
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const field = page.getByRole('textbox', { name: 'Search films and TV shows' });
  await field.fill('slow'); await expect(page).toHaveURL(/q=slow/);
  await expect(page.getByRole('button', { name: 'Current result', exact: true })).toHaveCount(0);
  await field.fill('fast'); await expect(page).toHaveURL(/q=fast/);
  await expect(page.getByRole('button', { name: 'Current result', exact: true })).toBeAttached();
  await expect(field).toBeFocused(); await page.waitForTimeout(950);
  await expect(page.getByRole('button', { name: 'Obsolete result', exact: true })).toHaveCount(0);
  await field.fill('empty'); await expect(page).toHaveURL(/q=empty/); await expect(page.locator('#status')).toContainText('No results');
  await field.fill('broken'); await expect(page.getByRole('button', { name: 'Try again', exact: true })).toBeAttached();
  await expect(field).toBeEnabled(); await expect(field).toBeFocused();
});
