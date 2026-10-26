import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: 'test/e2e', workers: 1, timeout: 30_000,
  use: { baseURL: 'http://127.0.0.1:5175', viewport: { width: 1920, height: 1080 }, headless: true },
  projects: [
    { name: 'fixtures', testMatch: ['app.spec.ts', 'gradient-mask.spec.ts', 'continue-watching.spec.ts', 'catalog-shelves.spec.ts'] },
    { name: 'live-browser', testMatch: 'live.spec.ts', use: { baseURL: 'http://127.0.0.1:5176' } },
  ],
  webServer: [
    { command: 'npm run start:fixtures -- --port 5175 --strictPort', url: 'http://127.0.0.1:5175', reuseExistingServer: !process.env.CI },
    { command: 'npm start -- --port 5176 --strictPort', url: 'http://127.0.0.1:5176', reuseExistingServer: !process.env.CI },
  ],
});
