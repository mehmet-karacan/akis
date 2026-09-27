import { expect, test } from '@playwright/test'

for (const viewport of [{ width: 390, height: 740 }, { width: 1440, height: 900 }]) {
  test(`export status fits ${viewport.width}px and exposes the JSON download`, async ({ page, context }) => {
    await page.setViewportSize(viewport)
    const job = {
      uuid: 'test-export', providerId: 'runs', resourceId: 'run-history', scope: 'ALL',
      status: 'COMPLETED', processedRows: 100, resultRows: 100, byteSize: 1024,
      createdAt: '2026-09-26T12:00:00Z', expiryAt: '2026-09-27T12:00:00Z',
    }
    await context.route('**/api/v1/projects/test-project/exports**', async route => {
      if (route.request().method() === 'POST') {
        expect(route.request().postDataJSON()).toMatchObject({ scope: 'ALL', includeDetails: true, locale: 'tr' })
      }
      return route.fulfill({ json: job })
    })
    await page.goto('/e2e/fixtures/export.html')
    await context.addCookies([{ name: 'XSRF-TOKEN', value: 'fixture-token', url: new URL(page.url()).origin }])
    await page.getByRole('button', { name: 'Dışa Aktar', exact: true }).click()
    await page.getByRole('menuitem', { name: 'Tüm kayıtlar' }).click()
    await expect(page.getByText('Dışa Aktarma · Tamamlandı')).toBeVisible()
    const dialog = page.getByRole('dialog')
    const bounds = await dialog.boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.y).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width)
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(viewport.height)
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth && document.documentElement.scrollHeight <= window.innerHeight)).toBe(true)
    // Edge native downloads bypass request routing. Capture the real generated
    // anchor before navigation so this fixture never reaches a live export API.
    await page.evaluate(() => {
      document.addEventListener('click', event => {
        if (event.target instanceof HTMLAnchorElement && event.target.hasAttribute('download')) {
          event.preventDefault()
          document.body.dataset.exportHref = event.target.href
        }
      }, true)
    })
    await page.getByRole('button', { name: 'JSON İndir' }).click()
    await expect(page.locator('body')).toHaveAttribute('data-export-href', new URL('/api/v1/projects/test-project/exports/test-export/download', page.url()).href)
    await page.getByRole('button', { name: 'Kapat', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await page.getByRole('button', { name: 'Aktarma Durumu' }).click()
    await expect(page.getByRole('button', { name: 'JSON İndir' })).toBeVisible()
  })
}
