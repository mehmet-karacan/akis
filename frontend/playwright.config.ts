import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.AKIS_E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    channel: process.env.AKIS_E2E_BROWSER_CHANNEL ?? 'msedge',
    locale: 'en-GB',
    launchOptions: { slowMo: Number(process.env.AKIS_E2E_SLOW_MO ?? 0) },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: process.env.AKIS_E2E_VIDEO === '1' ? 'retain-on-failure' : 'off',
  },
})
