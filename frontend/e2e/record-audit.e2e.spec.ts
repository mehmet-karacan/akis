import { expect, test } from '@playwright/test'
import { login, expectNoHorizontalOverflow } from './fixtures'

test('record icons and attribution remain visible in cards list table and mobile', async ({ page }) => {
  test.setTimeout(90_000)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await login(page)
  const responsePromise = page.waitForResponse(response => response.url().includes('/record-audit/connections'))
  await page.goto('/project/connections')
  const response = await responsePromise
  expect(response.ok()).toBeTruthy()
  const records = await response.json()
  expect(records.length).toBeGreaterThan(0)
  await page.route('**/record-audit/connections', route => route.fulfill({ json: records.map((record: { uuid: string }) => ({ ...record, createdBy: 'Audit Creator', updatedBy: 'Audit Editor', createdAt: '2026-09-15T09:00:00Z', updatedAt: '2026-09-15T10:00:00Z' })) }))
  await page.reload()
  for (const name of ['Cards', 'List', 'Table']) {
    await page.getByRole('radio', { name, exact: true }).locator('..').click()
    await expect(page.getByText('Audit Creator', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Audit Editor', { exact: true }).first()).toBeVisible()
    await expect(page.locator('[data-field-icon="createdBy"]').first()).toBeVisible()
    await expect(page.locator('[data-field-icon="updatedBy"]').first()).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: `test-results/audit-${name}.png`, fullPage: true })
  }
  await page.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()
  await page.evaluate(() => localStorage.setItem('akis.theme', 'dark'))
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByText('Audit Creator', { exact: true }).first()).toBeVisible()
  await page.screenshot({ path: 'test-results/audit-dark.png', fullPage: true })
  await page.locator('.connection-record-card').first().screenshot({ path: 'test-results/audit-dark-card.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expectNoHorizontalOverflow(page)
  await page.getByText('Audit Editor', { exact: true }).first().scrollIntoViewIfNeeded()
  await expect(page.getByText('Audit Editor', { exact: true }).first()).toBeVisible()
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: 'test-results/audit-mobile.png', fullPage: true })
})
