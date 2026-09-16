import { expect, test } from '@playwright/test'
// Isolated UI fixture, not live Oracle acceptance.
test('KM run evidence fits desktop and mobile in both themes', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/e2e/fixtures/km-run.html')
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => localStorage.setItem('akis.theme', value), theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    for (const width of [1366, 390]) {
      await page.setViewportSize({ width, height: 768 })
      await expect(page.getByText('AKIS_LOAD_EXAMPLE')).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      await page.screenshot({ path: `test-results/km-run-${theme}-${width}.png`, fullPage: true })
    }
  }
  expect(errors).toEqual([])
})
