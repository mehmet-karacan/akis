import { expect, test } from '@playwright/test'

test('overview audit table and model navigation tree stay usable together', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('akis.language', 'tr')
    localStorage.setItem('akis.lastProjectUuid', 'fixture')
  })
  const model = { uuid: 'model', logicalSchemaUuid: 'logical', code: 'SALES', name: 'Satış Modeli', status: 'AKTIF', version: 1 }
  const folders = [
    { uuid: 'sales-folder', modelUuid: 'model', parentUuid: null, code: 'SALES_FOLDER', name: 'Satış Klasörü', version: 1 },
    { uuid: 'archive-folder', modelUuid: 'model', parentUuid: 'sales-folder', code: 'ARCHIVE', name: 'Arşiv', version: 1 },
  ]
  const objects = [
    { uuid: 'orders', modelUuid: 'model', submodelUuid: 'archive-folder', code: 'ORDERS', name: 'Siparişler', objectReference: 'ORDERS', type: 'TABLE', status: 'AKTIF', version: 1 },
    ...Array.from({ length: 30 }, (_, index) => ({ uuid: `root-${index}`, modelUuid: 'model', submodelUuid: null, code: `TABLE_${index}`, name: `Kaynak Tablo ${index}`, objectReference: `TABLE_${index}`, type: 'TABLE', status: 'AKTIF', version: 1 })),
  ]
  await page.route(/\/api\/v\d+\//, async route => {
    const path = new URL(route.request().url()).pathname
    let json: unknown = []
    if (path === '/api/v1/auth/me') json = { id: 1, uuid: 'fixture-user', kullaniciKodu: 'fixture', gorunenAd: 'Fixture User' }
    if (path === '/api/v1/auth/csrf') json = { token: 'fixture-csrf', headerName: 'X-XSRF-TOKEN', parameterName: '_csrf' }
    if (path === '/api/v1/projects/fixture') json = { uuid: 'fixture', name: 'Örnek Proje', code: 'ORNEK', status: 'AKTIF', version: 1 }
    if (path.endsWith('/access')) json = { roles: [], permissions: ['KATALOG_GORUNTULE', 'KATALOG_KESFET', 'TANIM_GORUNTULE'] }
    if (path.endsWith('/models')) json = [model]
    if (path.endsWith('/models/model')) json = model
    if (path.endsWith('/submodels')) json = folders
    if (path.endsWith('/data-objects')) json = objects
    if (path.endsWith('/environments')) json = [{ uuid: 'environment', code: 'TEST', name: 'Test Ortamı', defaultEnvironment: true, status: 'ETKIN', version: 1 }]
    if (path.endsWith('/schema-bindings')) json = [{ uuid: 'binding', logicalSchemaUuid: 'logical', environmentUuid: 'environment', physicalSchemaUuid: 'physical', databaseType: 'ORACLE' }]
    if (path.endsWith('/physical-schemas')) json = [{ uuid: 'physical', connectionUuid: 'connection', schemaName: 'APP', name: 'APP', code: 'APP', databaseType: 'ORACLE', status: 'ETKIN' }]
    if (path.endsWith('/connections')) json = [{ uuid: 'connection', code: 'DB', name: 'Kaynak Bağlantı', databaseType: 'ORACLE', status: 'ETKIN' }]
    if (path.endsWith('/definitions/recent')) json = [{ uuid: 'recent', folderUuid: null, type: 'PROCEDURE', code: 'CLEANUP', status: 'AKTIF', name: 'Temizlik', description: null, version: 1 }]
    if (path.endsWith('/record-audit/definitions')) json = [{ uuid: 'recent', createdBy: 'Mehmet Karacan', createdAt: '2026-09-24T10:30:00Z', updatedBy: 'Developer', updatedAt: '2026-09-25T11:30:00Z' }]
    await route.fulfill({ json })
  })
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto('/project')
  const recent = page.getByRole('table', { name: 'Son tanımlanan 5 nesne tablosu' })
  await expect(recent).toBeVisible()
  await expect(recent.getByRole('cell', { name: 'Mehmet Karacan' })).toBeVisible()
  await expect(recent.getByRole('link', { name: 'Temizlik', exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/overview-recent-table.png', fullPage: true })
  for (const width of [768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  }
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.getByRole('menuitem', { name: 'Modeller', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Satış Modeli', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: 'Satış Klasörü' })).toBeVisible()
  await page.getByRole('menuitem', { name: 'Modeli Aç' }).click()
  await page.setViewportSize({ width: 1366, height: 720 })
  const modelPage = page.locator('.model-detail-page')
  const modelList = page.locator('.model-integrated-workspace > .connections-records')
  const reversePanel = page.locator('.model-integrated-reverse')
  await expect(reversePanel.getByRole('combobox', { name: 'Keşif ortamı' })).toHaveCount(0)
  await expect(reversePanel.getByText('Test Ortamı')).toBeVisible()
  await expect(reversePanel.getByRole('button', { name: 'Nesneleri Keşfet' })).toBeVisible()
  await expect(reversePanel.locator('.metadata-context-summary .provider-cell')).toHaveCount(0)
  await expect.poll(() => modelPage.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await expect.poll(() => modelList.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await expect.poll(() => reversePanel.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await modelList.evaluate(element => { element.scrollTop = element.scrollHeight })
  await expect.poll(() => modelList.evaluate(element => element.scrollTop > 0)).toBe(true)
  await modelPage.evaluate(element => { element.scrollTop = element.scrollHeight })
  await expect.poll(() => modelPage.evaluate(element => element.scrollTop > 0)).toBe(true)
  await page.screenshot({ path: 'test-results/model-integrated-scroll.png', fullPage: true })
  const modelToggle = page.getByRole('menuitem', { name: 'Satış Modeli', exact: true })
  await modelToggle.click()
  await expect(modelToggle).toHaveAttribute('aria-expanded', 'false')
  await modelToggle.click()
  await expect(modelToggle).toHaveAttribute('aria-expanded', 'true')
  const folderToggle = page.getByRole('menuitem', { name: 'Satış Klasörü' })
  await folderToggle.click()
  await page.getByRole('menuitem', { name: 'Klasörü Aç' }).click()
  await expect(page).toHaveURL(/folder=SALES_FOLDER/)
  await expect(folderToggle).toHaveAttribute('aria-expanded', 'true')
  await folderToggle.click()
  await expect(folderToggle).toHaveAttribute('aria-expanded', 'false')
  await folderToggle.click()
  await expect(folderToggle).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('menuitem', { name: 'Arşiv' }).click()
  await page.getByRole('menuitem', { name: 'Klasörü Aç' }).last().click()
  await page.getByRole('menuitem', { name: 'Siparişler' }).click()
  await expect(page).toHaveURL(/\/project\/models\/SALES\?folder=ARCHIVE&object=ORDERS/)
  await page.locator('.ant-modal-close').click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const modelsToggle = page.getByRole('menuitem', { name: 'Modeller', exact: true })
  await modelsToggle.click()
  await expect(modelsToggle).toHaveAttribute('aria-expanded', 'false')
  await modelsToggle.click()
  await expect(modelsToggle).toHaveAttribute('aria-expanded', 'true')
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/overview-model-tree.png', fullPage: true })
})
