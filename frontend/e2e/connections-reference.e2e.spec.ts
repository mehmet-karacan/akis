import { expect, test } from '@playwright/test'
import { expectHealthyScreen, expectNoHorizontalOverflow, login, navigateInApp } from './fixtures'

test('connection catalog follows the schema metadata reference in light and dark themes', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('pageerror', error => runtimeErrors.push(error.message))
  await login(page)

  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate(nextTheme => localStorage.setItem('akis.theme', nextTheme), theme)
    await page.reload()
    await navigateInApp(page, '/project/connections')
    await page.setViewportSize({ width: 1920, height: 1080 })
    await expectHealthyScreen(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.getByText('CONNECTION DEFINITIONS', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Connections', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Connection Catalog', exact: true })).toBeVisible()

    const summaryCards = page.locator('.ui-summary-card')
    await expect(summaryCards).toHaveCount(4)
    const summaryColors = await summaryCards.locator('.ui-summary-icon').evaluateAll(nodes => nodes.map(node => getComputedStyle(node).color))
    expect(new Set(summaryColors).size).toBe(4)

    const cards = page.locator('.ui-grid-records--card .ui-grid-record')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
    const firstCard = cards.first()
    await expect(firstCard.locator('header .database-provider-icon')).toBeVisible()
    await expect(firstCard.locator('header .connection-status')).toBeVisible()
    await expect(firstCard.locator('.ui-record-card-fields dt').filter({ hasText: /^Provider$/ })).toHaveCount(0)
    await expect(firstCard.locator('.ui-record-card-fields dt').filter({ hasText: /^Status$/ })).toHaveCount(0)
    await expect(firstCard.locator('.ui-record-card-fields > dl > div').first()).toHaveCSS('grid-column-start', '1')
    await expect(firstCard.locator('.ui-record-audit--footer')).toBeVisible()
    await expect(firstCard.getByRole('button', { name: /^View:/ })).toBeVisible()
    const cardActionBoxes = await firstCard.locator('.ui-grid-footer-action button').evaluateAll(buttons => buttons.map(button => button.getBoundingClientRect()).map(({ x, y, width, height }) => ({ x, y, width, height })))
    expect(cardActionBoxes).toHaveLength(2)
    expect(Math.abs(cardActionBoxes[0].y - cardActionBoxes[1].y)).toBeLessThan(2)
    await expect(firstCard.locator('[data-field-key="Description"]')).toHaveCSS('grid-column-start', '1')
    await firstCard.locator('[data-field-key="Description"]').click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    await page.getByRole('radio', { name: 'List', exact: true }).locator('..').click()
    await expect(page.locator('.ui-grid-records--list .ui-grid-record')).toHaveCount(count)
    await expect(page.locator('.ui-grid-records--list .ui-record-audit--footer').first()).toBeVisible()
    await expect(page.locator('.ui-grid-records--list .ui-grid-record').first().locator('header .database-provider-icon')).toBeVisible()
    await expect(page.locator('.ui-grid-records--list .ui-grid-record').first().locator('header .connection-status')).toBeVisible()
    await expect(page.locator('.ui-grid-records--list .ui-grid-record').first().locator('[data-field-key="Provider"]')).toHaveCount(0)
    await expect(page.locator('.ui-grid-records--list .ui-grid-record').first().locator('[data-field-key="Description"]')).toHaveCSS('grid-column-start', '1')
    await page.locator('.ui-grid-records--list .ui-grid-record').first().locator('[data-field-key="Description"]').click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.screenshot({ path: `test-results/connections-reference-list-${theme}.png`, fullPage: true })

    await page.getByRole('radio', { name: 'Table', exact: true }).locator('..').click()
    await expect(page.locator('.ui-data-grid tbody tr.ant-table-row')).toHaveCount(count)
    await expect(page.locator('.ui-data-grid thead th')).not.toHaveCount(0)
    expect(await page.locator('.ui-data-grid thead th').evaluateAll(headers => headers.every(header => Boolean(header.querySelector('svg'))))).toBe(true)
    await page.locator('.ui-data-grid tbody tr.ant-table-row').first().locator('td').nth(2).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.screenshot({ path: `test-results/connections-reference-table-${theme}.png`, fullPage: true })

    await page.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()
    await page.locator('.ui-grid-record').first().getByRole('button', { name: /^View:/ }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    const dialogCssWidth = await page.locator('.ant-modal.connection-catalog-dialog').evaluate(element => Number.parseFloat(getComputedStyle(element).width))
    expect(dialogCssWidth).toBeGreaterThan(1200)
    await expect(dialog.locator('.connection-dialog-title svg')).toBeVisible()
    await expect(dialog.locator('.connection-panel-description svg')).toBeVisible()
    expect(await dialog.locator('.connection-tab-label svg').count()).toBeGreaterThanOrEqual(2)
    await dialog.screenshot({ path: `test-results/connections-reference-detail-${theme}.png` })
    await dialog.getByRole('tab', { name: /Physical Schemas/ }).click()
    await expect(dialog.getByRole('tab', { name: /Physical Schemas/ })).toHaveAttribute('aria-selected', 'true')
    await dialog.screenshot({ path: `test-results/connections-reference-detail-physical-${theme}.png` })
    const workAreaTab = dialog.getByRole('tab', { name: /Work Area/ })
    if (await workAreaTab.count()) {
      await workAreaTab.click()
      await expect(dialog.getByRole('heading', { name: 'Work Area', exact: true })).toBeVisible()
      await dialog.screenshot({ path: `test-results/connections-reference-detail-work-area-${theme}.png` })
    }
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)

    await page.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()
    const cardHeader = page.locator('.ui-grid-records--card .ui-grid-record').first().locator('header')
    await expect(cardHeader.locator('.database-provider-icon')).toBeVisible()
    await expect(cardHeader.locator('.provider-cell > span:last-child')).toBeHidden()
    await expect(cardHeader.locator('.ui-grid-record-header-title')).toBeVisible()

    await page.getByRole('radio', { name: 'Table', exact: true }).locator('..').click()
    await expect(page.locator('.ui-data-grid thead th').filter({ hasText: 'Provider' })).toBeVisible()
    await expect(page.locator('.ui-data-grid tbody td').filter({ hasText: 'Oracle' }).first()).toBeVisible()
    await page.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()

    await page.evaluate(() => {
      window.scrollTo(0, 0)
      document.querySelectorAll<HTMLElement>('*').forEach(element => { if (element.scrollTop > 0) element.scrollTop = 0 })
    })
    await page.screenshot({ path: `test-results/connections-reference-${theme}.png`, fullPage: true })

    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoHorizontalOverflow(page)
    await expect(page.locator('.ui-grid-records--card')).toHaveCSS('grid-template-columns', /.+/)
  }

  expect(runtimeErrors).toEqual([])
})
