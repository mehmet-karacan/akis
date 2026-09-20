import { expect, test } from '@playwright/test'

for (const theme of ['light', 'dark']) test(`record views preserve values and peer alignment in ${theme}`, async ({ page }) => {
  await page.addInitScript(mode => localStorage.setItem('akis.theme', mode), theme)
  await page.route(/\/api\/v\d+\//, route => route.abort())
  await page.setViewportSize({ width: 1366, height: 1000 })
  await page.goto('/e2e/fixtures/record-catalog.html')
  const cards = page.locator('.ui-record-card')
  await expect(cards).toHaveCount(2)
  for (const selector of ['.ui-record-card-audit', '.ui-record-card-description', 'footer', 'footer time', 'footer button[title="Edit"]']) {
    const positions = await cards.locator(selector).evaluateAll(elements => elements.map(element => element.getBoundingClientRect().y))
    expect(positions.length).toBe(2)
    expect(Math.abs(positions[0]! - positions[1]!)).toBeLessThan(2)
  }
  await expect(page.getByRole('region', { name: 'Record Information', exact: true })).toHaveCount(2)
  const long = cards.filter({ has: page.getByText('LONG', { exact: true }) })
  const values = await long.locator('dd').allTextContents()
  for (const field of await long.locator('.ui-record-audit dd').all()) {
    expect(await field.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    await expect(field).toHaveCSS('white-space', 'normal')
  }
  const lastTest = (await long.locator('time').textContent())!
  await long.getByText('LONG', { exact: true }).click()
  await expect(page.getByLabel('Opened record')).toBeEmpty()
  await long.getByRole('button', { name: 'Edit: LONG', exact: true }).click()
  await expect(page.getByLabel('Opened record')).toHaveText('LONG')
  await page.locator('label').filter({ has: page.getByRole('radio', { name: 'List', exact: true }) }).click()
  expect(await long.locator('dd').allTextContents()).toEqual(values)
  await expect(long.locator('time')).toHaveText(lastTest)
  await page.locator('label').filter({ has: page.getByRole('radio', { name: 'Table', exact: true }) }).click()
  const row = page.getByRole('row').filter({ has: page.getByText('LONG', { exact: true }) })
  for (const value of values) await expect(row).toContainText(value)
  await expect(row).toContainText('Test passed')
  await expect(row.getByRole('button', { name: 'Edit: LONG', exact: true })).toBeAttached()
  await page.locator('label').filter({ has: page.getByRole('radio', { name: 'Cards', exact: true }) }).click()
  for (const width of [1366, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    for (const card of await cards.all()) {
      const bounds = (await card.boundingBox())!
      for (const button of await card.getByRole('button').all()) {
        const box = (await button.boundingBox())!
        expect(box.x).toBeGreaterThanOrEqual(bounds.x)
        expect(box.x + box.width).toBeLessThanOrEqual(bounds.x + bounds.width + 1)
        expect(box.y + box.height).toBeLessThanOrEqual(bounds.y + bounds.height + 1)
      }
    }
    await page.screenshot({ path: `test-results/records-${theme}-${width}.png`, fullPage: true })
  }
})
