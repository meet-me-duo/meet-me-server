import { defineConfig } from '@playwright/test';
const permitted = ['managed-browser.spec.ts', 'host-creation.spec.ts', 'auth-recovery.spec.ts'];
const spec = process.env.MANAGED_BROWSER_SPEC;
if (!permitted.includes(spec)) throw new Error('EXACT_MANAGED_BROWSER_SUITE_REQUIRED');
export default defineConfig({
  testDir: '.', testMatch: spec, fullyParallel: false, workers: 1, retries: 0,
  timeout: 60000, expect: { timeout: 10000 },
  reporter: [['list'], ['json', { outputFile: process.env.HOST_INTEGRATION_REPORT }]],
  outputDir: process.env.HOST_INTEGRATION_OUTPUT,
  use: { browserName: 'chromium', baseURL: 'https://localhost:19443', ignoreHTTPSErrors: false, trace: 'off', video: 'off', screenshot: 'off' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 } } },
  ],
});
