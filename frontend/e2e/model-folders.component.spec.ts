import { expect, test } from '@playwright/test'

// Production routes, synthetic session/catalog. PATCH is intercepted: this
// proves screen behavior, not persistence against the production database.
for (const theme of ['light', 'dark']) test(`model folders preserve the selected object in ${theme}`, async ({ page }) => {
  await page.addInitScript(mode => {
    localStorage.setItem('akis.theme', mode)
    localStorage.setItem('akis.language', 'en')
    localStorage.setItem('akis.lastProjectUuid', 'fixture')
    sessionStorage.setItem('akis.localSession', JSON.stringify({ username: 'Fixture User', authorization: 'Basic Zml4dHVyZTpmaXh0dXJl' }))
  }, theme)
  const model = { uuid: 'model', logicalSchemaUuid: 'logical', name: 'Orders Model', code: 'ORDERS', technologyCode: 'ORACLE', status: 'AKTIF', version: 1 }
  const folders = [{ uuid: 'business', modelUuid: 'model', name: 'Business', code: 'B', version: 1 },
    { uuid: 'sales', modelUuid: 'model', parentUuid: 'business', name: 'Sales', code: 'S', version: 1 }]
  let object = { uuid: 'orders', modelUuid: 'model', submodelUuid: null as string | null, name: 'Orders View', code: 'ORDERS_VIEW', objectReference: 'APP.ORDERS_VIEW', type: 'VIEW', status: 'AKTIF', version: 1 }
  let writes = 0
  await page.route(/\/api\/v\d+\//, async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (request.method() === 'PATCH') {
      expect(path).toBe('/api/v1/projects/fixture/models/model/data-objects/orders/folder')
      expect(request.postDataJSON()).toEqual({ submodelUuid: writes === 0 ? 'sales' : null, expectedVersion: writes + 1 })
      writes++
      object = { ...object, submodelUuid: request.postDataJSON().submodelUuid, version: object.version + 1 }
      return route.fulfill({ json: object })
    }
    expect(request.method()).toBe('GET')
    let json: unknown = []
    if (path === '/api/v1/projects/fixture') json = { uuid: 'fixture', name: 'Fixture Project', code: 'FIXTURE', status: 'AKTIF', version: 1 }
    if (path.endsWith('/access')) json = { roles: [], permissions: ['KATALOG_GORUNTULE', 'KATALOG_KESFET', 'TANIM_GORUNTULE'] }
    if (path.endsWith('/models')) json = [model]
    if (path.endsWith('/models/model')) json = model
    if (path.endsWith('/submodels')) json = folders
    if (path.endsWith('/data-objects')) json = [object]
    if (path.endsWith('/record-audit/data-objects')) json = [{ uuid: 'orders', createdBy: 'Fixture Creator', createdAt: '2026-01-01T00:00:00Z', updatedBy: writes ? `Folder Editor ${writes}` : null, updatedAt: writes ? '2026-01-02T00:00:00Z' : null }]
    if (path.endsWith('/logical-schemas')) json = [{ uuid: 'logical', name: 'Logical Orders', code: 'L', status: 'AKTIF', version: 1 }]
    await route.fulfill({ json })
  })
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto('/project/models/model?object=orders')
  await expect(page.getByRole('heading', { name: 'Orders View', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add Folder', exact: true })).toBeVisible()
  const editor = page.getByRole('region', { name: 'Data Store Folder', exact: true })
  await editor.getByRole('combobox').click()
  await page.getByTitle('Business / Sales', { exact: true }).click()
  await editor.getByRole('button', { name: 'Update Folder', exact: true }).click()
  await expect(page).toHaveURL(/folder=sales&object=orders/)
  await expect(page.getByRole('heading', { name: 'Orders Model / Sales', exact: true })).toBeVisible()
  await expect(page.locator('.model-datastore-catalog').getByText('Folder Editor 1', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Orders View', exact: true })).toBeVisible()
  await page.reload()
  await expect(editor.getByRole('button', { name: 'Update Folder', exact: true })).toBeDisabled()
  await editor.getByRole('combobox').click()
  await page.getByTitle('Model Root', { exact: true }).click()
  await editor.getByRole('button', { name: 'Update Folder', exact: true }).click()
  await expect(page).toHaveURL(/model\?object=orders$/)
  await expect(page.locator('.model-datastore-catalog').getByText('Folder Editor 2', { exact: true })).toBeVisible()
  expect(writes).toBe(2)
  for (const width of [1366, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await editor.scrollIntoViewIfNeeded()
    await expect(editor.getByRole('button', { name: 'Update Folder', exact: true })).toBeInViewport({ ratio: 1 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  }
  await page.screenshot({ path: `test-results/model-folder-${theme}.png`, fullPage: true })
})
