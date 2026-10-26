// Local visual QA only. Run against `npm start -- --port 5184`.
// Artwork: existing Blender Foundation Big Buck Bunny poster, CC BY 3.0.
import { chromium } from '@playwright/test';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const base = process.argv[2] || 'http://127.0.0.1:5184';
const output = '.impeccable/review';
await mkdir(output, { recursive: true });
const fixtures = JSON.parse(await readFile('test/fixtures/http.json', 'utf8'));
const poster = await readFile('android/app/src/androidTest/assets/qa-big-buck-bunny.jpg');
const profiles = Array.from({ length: 7 }, (_, index) => ({ id: index ? `qa-${index}` : 'default', name: ['My profile', 'Nora', 'Kai', 'Alex', 'Sam', 'Robin', 'Ari'][index], avatar: index, quality: 0, autoplay: true, largerBuffer: false, locked: index === 1 }));
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
for (const viewport of [{ width: 1920, height: 1080 }, { width: 1280, height: 720 }]) {
  const context = await browser.newContext({ viewport, recordVideo: { dir: `${output}/clips`, size: viewport } });
  const page = await context.newPage(); const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(({ profiles, hash }) => {
    localStorage.setItem('flixtor-live:app', JSON.stringify({ schema: 1, profiles, libraries: Object.fromEntries(profiles.map(profile => [profile.id, { favorites: [], viewing: [] }])), skip: {}, fit: { horizontal: 5, vertical: 5 } }));
    localStorage.setItem('flixtor-live:pin:qa-1', hash);
  }, { profiles, hash: createHash('sha256').update('qa-1:1234').digest('hex') });
  await page.route('**/qa-poster.jpg', route => route.fulfill({ contentType: 'image/jpeg', body: poster }));
  await page.route('**/__platform/call', async route => {
    const { method, args } = route.request().postDataJSON(); let value = true;
    if (method === 'art') value = '/qa-poster.jpg';
    if (method === 'media') value = { uri: '/test/fixtures/browser-player.webm', captions: [] };
    if (method === 'http') {
      const path = new URL(args.url).pathname;
      let body = fixtures[path] || (path.startsWith('/watch/') ? fixtures.detail : path.startsWith('/ajax/v4/') ? fixtures.stream : path.startsWith('/show/') || path.startsWith('/search/') ? fixtures.catalog : '{}');
      body = body.replaceAll('<div class=front>', '<div class=front><img src="https://fixture.example/poster.jpg">');
      if (path.startsWith('/watch/')) body += '<meta property="og:image" content="https://fixture.example/poster.jpg"><section style="background-image:url(https://fixture.example/backdrop.jpg)"></section>';
      value = { status: 200, location: '', body: path.startsWith('/ajax/v4/') ? body : '<meta name=csrf-token content=qa-token>' + body };
    }
    await route.fulfill({ json: { value } });
  });
  const activate = async name => { const button = page.getByRole('button', { name, exact: true }).first(); await button.focus(); await page.keyboard.press('Enter'); };
  const capture = async name => { await page.waitForTimeout(900); await page.screenshot({ path: `${output}/${name}-${viewport.height}.png` }); };
  await page.goto(base); await page.getByRole('button', { name: 'My profile', exact: true }).waitFor(); await capture('profiles');
  await activate('Nora'); await page.getByRole('dialog', { name: 'Unlock Nora' }).waitFor({ state: 'attached' }); await capture('pin'); await page.keyboard.press('Escape');
  await activate('My profile'); await page.getByRole('button', { name: 'The Test Series', exact: true }).waitFor(); await capture('home');
  await activate('The Test Series'); await page.getByRole('button', { name: '1. Chapter 1', exact: true }).waitFor(); await capture('detail');
  await activate('1. Chapter 1'); await page.getByRole('button', { name: 'Pause', exact: true }).waitFor(); await activate('Settings'); await capture('player-settings');
  await page.keyboard.press('Escape'); await page.keyboard.press('Escape'); await page.keyboard.press('Escape');
  await activate('Search'); await page.getByRole('textbox', { name: 'Search films and TV shows' }).fill('story'); await page.waitForURL(/q=story/); await capture('search');
  await activate('Settings'); await capture('settings');
  if (errors.length) throw new Error(errors.join('\n'));
  await context.close();
}
await browser.close();
await writeFile(`${output}/capture-notes.md`, '# Liquid Cinema visual QA\n\nBrowser captures: 1280 × 720 and 1920 × 1080. Synthetic catalogue/account data; all artwork slots deliberately use the existing Big Buck Bunny poster to stress-test bright artwork. No live VIP or catalogue access is claimed.\n\nSource: android/app/src/androidTest/assets/qa-big-buck-bunny.jpg. Blender Foundation, CC BY 3.0; attribution and source in its sibling README.md. Interaction clips are in clips/. Native emulator screenshots are separate.\n');
console.log('Captured profiles, PIN, Home, detail, search, settings and player drawer at 720p/1080p.');
