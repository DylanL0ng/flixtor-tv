import { defineConfig } from 'vitest/config';
import solid from 'vite-plugin-solid';
import { browserBridge } from './tools/browser-bridge.ts';

export default defineConfig({
  base: './',
  plugins: [browserBridge(), solid({ solid: { moduleName: '@solidtv/solid', generate: 'universal' } })],
  resolve: { dedupe: ['solid-js', '@solidtv/solid', '@solidtv/renderer'] },
  optimizeDeps: { exclude: ['@solidtv/solid', '@solidtv/renderer'] },
  build: { target: 'chrome80', sourcemap: false },
  test: { environment: 'node', include: ['test/**/*.test.ts'] },
});
