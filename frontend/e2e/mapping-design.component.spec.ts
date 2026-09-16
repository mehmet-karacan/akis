import { expect, test } from '@playwright/test'

// Mocked backend component check, not physical mapping or Oracle acceptance.
test('mapping compatibility is explicit, versioned and fits narrow screens', async ({ page }) => {
  let requests = 0
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/mapping-design/assess', async route => {
    requests++
    const input = route.request().postDataJSON()
    expect(input.content.datasets[0].name).toBeUndefined()
    const supported = input.schemaVersion === 2
    await route.fulfill({ json: { shapeSupported: supported, executionVerified: false, capability: supported ? 'ORACLE_TABLE_COPY_V1' : 'DEFINITION_ONLY', reasonCode: supported ? null : 'MAPPING_SCHEMA_VERSION_REQUIRED', maximumSourceRows: 1000, remainingChecks: [] } })
  })
  await page.goto('/e2e/fixtures/mapping-design.html')
  const check = page.getByRole('button', { name: /Check Runtime Compatibility|Motor Uyumluluğunu Kontrol Et/ })
  await expect(check).toBeVisible()
  expect(requests).toBe(0)
  await check.click()
  await expect(page.getByText(/Definition Only — Runtime|Yalnız Tanım — Motor/)).toBeVisible()
  await page.getByRole('button', { name: /Upgrade Draft to Version 2|Taslağı Sürüm 2’ye Geçir/ }).click()
  await expect(page.getByText(/Definition Only — Runtime|Yalnız Tanım — Motor/)).toHaveCount(0)
  await check.click()
  await expect(page.getByText(/Compatible Shape — Execution Not Verified|Tanım Yapısı Uyumlu/)).toBeVisible()
  for (const width of [390, 1366]) {
    await page.setViewportSize({ width, height: 768 })
    await expect(check).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  }
  await page.screenshot({ path: 'test-results/mapping-design-component.png', fullPage: true })
  await page.evaluate(() => localStorage.setItem('akis.theme', 'dark'))
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(check).toBeVisible()
  await page.screenshot({ path: 'test-results/mapping-design-component-dark.png', fullPage: true })
  expect(errors).toEqual([])
})
