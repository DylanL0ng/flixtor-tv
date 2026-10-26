import { defineConfig } from 'vitest/config';
import solid from 'vite-plugin-solid';
import { browserBridge } from './tools/browser-bridge.ts';
import { gradientMask } from './tools/gradient-mask.ts';

export default defineConfig({
  base: './',
  plugins: [browserBridge(), gradientMask(), solid({ solid: { moduleName: '@solidtv/solid', generate: 'universal' } })],
  resolve: { dedupe: ['solid-js', '@solidtv/solid', '@solidtv/renderer'] },
  optimizeDeps: { exclude: ['@solidtv/solid', '@solidtv/renderer'] },
  build: { target: 'chrome80', sourcemap: false, rolldownOptions: { output: { codeSplitting: { groups: [{ name: 'tv-engine', test: /node_modules\/(?:@solidtv|solid-js)\// }] } } } },
  test: { environment: 'node', include: ['test/**/*.test.ts'] },
});
