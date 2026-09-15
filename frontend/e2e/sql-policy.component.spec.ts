import { expect, test } from '@playwright/test'

// Isolated component journey: no live database, execution or application login.
test('SQL check is explicit, errors are visible, and the editor fits both viewports', async ({ page }) => {
  let requests = 0
  const failures: string[] = []
  page.on('pageerror', (error) => failures.push(error.message))
  await page.route('**/api/v1/projects/test-project/sql/validate', async (route) => {
    requests++
    await route.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ detail: 'Unsupported SQL policy', code: 'SQL_POLICY_REJECTED' }) })
  })
  await page.goto('/e2e/fixtures/sql-policy.html')
  const check = page.getByRole('button', { name: /Check SQL|SQL’i Kontrol Et/ })
  await expect(check).toBeVisible()
  expect(requests).toBe(0)
  await check.click()
  await expect(page.getByText('Unsupported SQL policy')).toBeVisible()
  expect(requests).toBe(1)
  const editor = page.locator('.cm-content')
  await editor.fill('SELECT ID FROM TARGET_TABLE')
  await expect(page.getByText('Unsupported SQL policy')).toHaveCount(0)
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await expect(editor).toBeVisible()
  }
  await page.screenshot({ path: 'test-results/sql-policy-component.png', fullPage: true })
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark' })
  await expect.poll(() => page.locator('.cm-editor').evaluate((editor) => {
    const channels = getComputedStyle(editor).backgroundColor.match(/\d+/g)?.slice(0, 3).map(Number) ?? []
    return channels.length === 3 ? Math.max(...channels) : 255
  })).toBeLessThan(100)
  await page.screenshot({ path: 'test-results/sql-policy-component-dark.png', fullPage: true })
  expect(failures).toEqual([])
})
