import { expect, test } from '@playwright/test'

// Full production shell/route with synthetic read-only API responses.
for (const theme of ['light', 'dark']) for (const language of ['en', 'tr']) {
  test(`connection filter labels align in ${theme} ${language}`, async ({ page }) => {
    await page.addInitScript(({ mode, locale }) => {
      localStorage.setItem('akis.theme', mode)
      localStorage.setItem('akis.language', locale)
      localStorage.setItem('akis.lastProjectUuid', 'fixture')
      sessionStorage.setItem('akis.localSession', JSON.stringify({ username: 'Fixture User', authorization: 'Basic Zml4dHVyZTpmaXh0dXJl' }))
    }, { mode: theme, locale: language })
    await page.route(/\/api\/v\d+\//, async route => {
      expect(route.request().method()).toBe('GET')
      const path = new URL(route.request().url()).pathname
      let json: unknown = []
      if (path === '/api/v1/projects/fixture') json = { uuid: 'fixture', name: 'Fixture Project', code: 'FIXTURE', status: 'AKTIF', version: 1 }
      if (path.endsWith('/access')) json = { roles: [], permissions: ['BAGLANTI_YONET', 'KATALOG_GORUNTULE'] }
      await route.fulfill({ json })
    })
    await page.setViewportSize({ width: 1366, height: 900 })
    await page.goto('/project/connections')
    const fields = page.locator('.connection-management-panel .ui-filter-bar > label')
    await expect(fields).toHaveCount(3)
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      const rectangles = await fields.evaluateAll(labels => labels.map(label => {
        const text = label.querySelector(':scope > span')!.getBoundingClientRect()
        const control = label.querySelector('.ant-input, .ant-select')!.getBoundingClientRect()
        return { labelBottom: text.bottom, labelTop: text.top, controlTop: control.top, controlLeft: control.left, labelLeft: text.left }
      }))
      for (const field of rectangles) {
        expect(field.controlTop - field.labelBottom).toBeGreaterThanOrEqual(4)
        expect(Math.abs(field.controlLeft - field.labelLeft)).toBeLessThan(2)
      }
      if (width === 1366) expect(Math.max(...rectangles.map(field => field.controlTop)) - Math.min(...rectangles.map(field => field.controlTop))).toBeLessThan(2)
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    }
  })
}
