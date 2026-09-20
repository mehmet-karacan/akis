import { expect, test, type Page } from '@playwright/test'
import { expectHealthyScreen, expectNoHorizontalOverflow, login, navigateInApp } from './fixtures'

const primaryScreens = [
  '/project',
  '/project/objects',
  '/project/operations',
  '/project/connections',
  '/project/connections/new',
  '/project/logical-schemas',
  '/project/environments',
  '/project/models',
  '/project/team',
  '/project/publications',
  '/project/ui-kit',
]

async function assertNoRuntimeFailure(page: Page, failures: string[]) {
  await expectHealthyScreen(page)
  await expect(page.getByText('Failed to fetch', { exact: true })).toHaveCount(0)
  expect(failures, `Runtime failures: ${failures.join('\n')}`).toEqual([])
}

test.describe('all-screen quality gates', () => {
  test('every primary screen is responsive and free of uncaught runtime failures', async ({ page }) => {
    const failures: string[] = []
    page.on('pageerror', (error) => failures.push(`pageerror: ${error.message}`))

    await login(page)
    for (const path of primaryScreens) {
      await test.step(path, async () => {
        await navigateInApp(page, path)
        await assertNoRuntimeFailure(page, failures)
        for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
          await page.setViewportSize(viewport)
          await expectNoHorizontalOverflow(page)
        }
      })
    }
  })

  test('every primary screen remains usable in dark theme', async ({ page }) => {
    await login(page)
    await page.evaluate(() => localStorage.setItem('akis.theme', 'dark'))
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

    for (const path of primaryScreens) {
      await test.step(path, async () => {
        await navigateInApp(page, path)
        await expectHealthyScreen(page)
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
        await expectNoHorizontalOverflow(page)
      })
    }
  })

  test('successful and failed connection requests use visible feedback', async ({ page }) => {
    await login(page)
    await page.getByRole('menuitem', { name: /Connections|Bağlantılar/i }).click()
    await expect(page).toHaveURL(/\/project\/connections$/)
    await expectHealthyScreen(page)
    await page.route('**/api/v2/projects/*/connections/*/versions/*/tests', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Bağlantı servisine erişilemiyor', detail: 'Test ağı şu anda kapalı.', status: 503 }),
      })
    })
    await page.locator('.ui-grid-record').first().getByRole('button', { name: /View:|Görüntüle:/ }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: /Test Connection|Bağlantıyı Test Et/i }).click()
    await expect(page.locator('.ui-feedback-toast')).toContainText(/erişilemiyor|test ağı|başarısız|failed|cannot reach|could not complete/i)
  })
})
