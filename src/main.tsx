import { Config, createRenderer, loadFonts } from '@solidtv/solid';
import { AppRoot } from './app/App';
import { androidPlatform, type HostInfo, type Platform } from './platform/platform';
import { Repository } from './domain/repository';

Config.rendererOptions = {
  appWidth: 1920, appHeight: 1080, clearColor: 0x00000000, targetFPS: 60,
  textureMemory: { criticalThreshold: 64 * 1024 * 1024 },
  deviceLogicalPixelRatio: window.innerHeight / 1080, devicePhysicalPixelRatio: 1,
};
Config.fontSettings = { fontFamily: 'Roboto', color: 0xf4f6fcff, fontSize: 28 };
Config.animationsEnabled = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
Config.preventDefaultOnHandledKeys = true;
void loadFonts([
  { type: 'msdf', fontFamily: 'Roboto', atlasDataUrl: './fonts/Roboto-Regular.msdf.json', atlasUrl: './fonts/Roboto-Regular.msdf.png' },
  { type: 'msdf', fontFamily: 'Roboto700', atlasDataUrl: './fonts/Roboto-Bold.msdf.json', atlasUrl: './fonts/Roboto-Bold.msdf.png' },
]);
async function start() {
  let platform: Platform;
  if (window.nativeHost) platform = androidPlatform();
  else if (import.meta.env.DEV) platform = import.meta.env.MODE === 'fixtures'
    ? (await import('../test/fixture-platform')).fixturePlatform()
    : (await import('./platform/browser')).browserPlatform();
  else throw new Error('Open this application in its Android host.');
  const host = await platform.call<HostInfo>('ready');
  const repo = new Repository(platform); await repo.initialize();
  const { render } = createRenderer();
  render(() => <AppRoot platform={platform} host={host} repo={repo} />);
  document.querySelector('canvas')?.setAttribute('aria-hidden', 'true');
}
void start().catch(() => {
  const message = document.createElement('p');
  message.textContent = 'The TV interface could not start. Restart the app. Your saved data has been retained.';
  message.style.cssText = 'color:#f1efe8;background:#101214;padding:48px;font:28px sans-serif'; document.body.append(message);
});
