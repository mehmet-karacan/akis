import { expect, type Page } from '@playwright/test'

const username = process.env.AKIS_E2E_USERNAME
const password = process.env.AKIS_E2E_PASSWORD

export async function login(page: Page) {
  if (!username || !password) {
    throw new Error('AKIS_E2E_USERNAME and AKIS_E2E_PASSWORD must be set. Run scripts/run-e2e.ps1.')
  }

  await page.goto('/login')
  await page.locator('input[autocomplete="username"]').fill(username)
  await page.locator('input[autocomplete="current-password"]').fill(password)
  await page.locator('button[type="submit"]').click()
  await page.waitForURL(/\/project(?:[?#].*)?$/i)
  const projectUuid = await page.evaluate(() => localStorage.getItem('akis.lastProjectUuid'))
  expect(projectUuid, 'A project must be selected after login').toBeTruthy()
  return projectUuid!
}

export async function navigateInApp(page: Page, path: string, expectedPath = path) {
  await page.evaluate((nextPath) => {
    window.history.pushState({}, '', nextPath)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, path)
  await expect(page).toHaveURL(new RegExp(`${escapeRegExp(expectedPath)}(?:[?#].*)?$`))
}

export async function expectHealthyScreen(page: Page) {
  await expect(page.locator('#main-content h1').first()).toBeVisible()
  await expect(page.locator('.ui-async-state.loading')).toHaveCount(0)
  await expect(page.locator('.ui-async-state.error')).toHaveCount(0)
  await expect(page.locator('.error-banner')).toHaveCount(0)
}

export async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), { message: 'The settled viewport must not overflow horizontally' }).toBeLessThanOrEqual(0)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
