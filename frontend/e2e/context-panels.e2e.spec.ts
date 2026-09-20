import { expect, test } from '@playwright/test'
import { login, expectNoHorizontalOverflow } from './fixtures'

test('catalogs share full-width layout and logical records edit and delete without navigation', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 1000 })
  await login(page)
  const bounds: { x: number; width: number }[] = []
  for (const path of ['operations', 'connections', 'logical-schemas', 'environments']) {
    await page.goto(`/project/${path}`)
    const surface = page.locator('#main-content .page-stack, #main-content .execution-page').first()
    await expect(surface).toBeVisible()
    await expect(page.getByText('No project selected', { exact: true })).toHaveCount(0)
    await expect(page.locator('.ui-async-state.loading')).toHaveCount(0)
    const box = await surface.boundingBox()
    const navigation = page.locator('.shell-navigation')
    await expect(navigation).toBeVisible()
    const navBox = await navigation.boundingBox()
    expect(navBox!.x + navBox!.width).toBeLessThanOrEqual(box!.x)
    for (const card of await page.locator('.ui-summary-card').all()) {
      const metric = await card.locator('strong').boundingBox()
      const icon = await card.locator('.ui-summary-icon').boundingBox()
      const cardBox = await card.boundingBox()
      expect(metric!.x).toBeGreaterThanOrEqual(icon!.x + icon!.width)
      expect(metric!.x + metric!.width).toBeLessThanOrEqual(cardBox!.x + cardBox!.width)
    }
    if (path === 'connections') {
      const summary = await page.locator('.ui-summary-strip').boundingBox()
      const records = await page.locator('.connections-records').boundingBox()
      expect(Math.abs(summary!.x - records!.x)).toBeLessThanOrEqual(1)
      expect(Math.abs(summary!.width - records!.width)).toBeLessThanOrEqual(1)
      await page.getByRole('radio', { name: 'Table', exact: true }).locator('..').click()
      await expect(page.locator('.connections-records .ant-table')).toBeVisible()
      await page.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()
    }
    if (path === 'logical-schemas' || path === 'environments') {
      const grid = page.locator('.ui-grid-container').first()
      await grid.getByRole('radio', { name: 'Cards', exact: true }).locator('..').click()
      await expect(grid.locator('.ui-grid-record').first()).toBeVisible()
      await grid.getByRole('radio', { name: 'List', exact: true }).locator('..').click()
      await expect(grid.locator('.ui-grid-records--list')).toBeVisible()
      await grid.getByRole('radio', { name: 'Table', exact: true }).locator('..').click()
    }
    bounds.push({ x: box!.x, width: box!.width })
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: `test-results/context-width-${path}.png` })
  }
  for (const box of bounds) {
    expect(Math.abs(box.x - bounds[0]!.x)).toBeLessThanOrEqual(2)
    expect(Math.abs(box.width - bounds[0]!.width)).toBeLessThanOrEqual(2)
  }
  let items = [{ uuid: 'panel-test', code: 'PANEL_TEST', name: 'Panel Test', description: '', version: 1, status: 'AKTIF' }]
  await page.route('**/api/v1/projects/*/logical-schemas**', async route => {
    const method = route.request().method()
    if (method === 'PATCH') {
      const body = route.request().postDataJSON()
      expect(body.expectedVersion).toBe(1)
      items = [{ ...items[0]!, name: body.name, version: 2 }]
      await route.fulfill({ json: items[0] }); return
    }
    if (method === 'DELETE') { expect(route.request().url()).toContain('expectedVersion=2'); items = []; await route.fulfill({ status: 204 }); return }
    await route.fulfill({ json: items })
  })
  await page.goto('/project/logical-schemas')
  await page.getByRole('button', { name: /^(Edit|Düzenle): Panel Test$/ }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.ui-async-state.loading')).toHaveCount(0)
  await expect(dialog.locator('.connection-detail-section')).toBeVisible()
  await page.screenshot({ path: 'test-results/context-logical-editor.png', animations: 'disabled' })
  await dialog.getByLabel('Name', { exact: true }).fill('Updated Panel')
  await expect(dialog.getByLabel('Code', { exact: true })).toHaveAttribute('readonly', '')
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page).toHaveURL(/\/project\/logical-schemas$/)
  await page.getByRole('button', { name: /^(Edit|Düzenle): Updated Panel$/ }).click()
  await dialog.getByRole('button', { name: 'Delete', exact: true }).click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: 'Delete', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText('Updated Panel', { exact: true })).toHaveCount(0)
  await expect(page).toHaveURL(/\/project\/logical-schemas$/)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.shell-navigation')).toHaveCount(0)
  await page.getByRole('button', { name: 'Workspaces', exact: true }).click()
  await expect(page.locator('.shell-navigation')).toBeVisible()
  await page.getByRole('menuitem', { name: 'Connections', exact: true }).click()
  await expect(page.locator('.shell-navigation')).toHaveCount(0)
  await expect(page).toHaveURL(/\/project\/connections$/)
  await expect(page.getByRole('heading', { name: 'Connections', exact: true })).toBeVisible()
  await expect(page.locator('.ui-grid-record').first()).toBeVisible()
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: 'test-results/context-mobile-navigation.png' })
})
