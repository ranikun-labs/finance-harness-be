import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.backend-integration.config';

const baseURL = String(baseConfig.use?.baseURL ?? 'http://127.0.0.1:4174');

export default defineConfig({
  ...baseConfig,
  testMatch: 'journal-lifecycle.integration.spec.ts',
  reporter: 'line',
  tsconfig: './tsconfig.app.json',
  use: {
    ...baseConfig.use,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  webServer: {
    command: 'node e2e/support/financeInvestmentLifecycleServer.mjs',
    url: `${baseURL}/app/journal/new?type=investment`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
