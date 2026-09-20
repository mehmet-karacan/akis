import { expect, test } from '@playwright/test'
import { DEFAULT_PROCEDURE } from '../src/features/definitions/defaults'

// Production routes and shell, synthetic session/catalog. Every API request is
// intercepted: this verifies layout/navigation, not authentication or persistence.
for (const theme of ['light', 'dark'] as const) test(`procedure fits the real application shell in ${theme}`, async ({ page }) => {
  await page.addInitScript(mode => {
    localStorage.setItem('akis.theme', mode)
    localStorage.setItem('akis.language', 'en')
    localStorage.setItem('akis.lastProjectUuid', 'fixture')
    sessionStorage.setItem('akis.localSession', JSON.stringify({ username: 'Fixture User', authorization: 'Basic Zml4dHVyZTpmaXh0dXJl' }))
  }, theme)
  const definition = { uuid: 'procedure', folderUuid: 'leaf', type: 'PROCEDURE', code: 'LOAD', name: 'Daily Load', status: 'ACTIVE', version: 1, description: 'Synthetic procedure for shell layout verification.' }
  await page.route(/\/api\/v\d+\//, async route => {
    expect(route.request().method()).toBe('GET')
    const path = new URL(route.request().url()).pathname
    let json: unknown = []
    if (path === '/api/v1/projects/fixture') json = { uuid: 'fixture', name: 'Fixture Project', code: 'FIXTURE', status: 'AKTIF', version: 1 }
    if (path.endsWith('/access')) json = { roles: [], permissions: ['TANIM_DUZENLE', 'TANIM_DOGRULA', 'TANIM_GORUNTULE'] }
    if (path.endsWith('/definitions')) json = [definition]
    if (path.endsWith('/folders')) json = ['root', 'child', 'leaf'].map((uuid, index, ids) => ({ uuid, parentUuid: ids[index - 1] ?? null, code: uuid.toUpperCase(), name: uuid, status: 'AKTIF', version: 1 }))
    if (path.endsWith('/draft')) json = { uuid: 'draft', schemaVersion: 2, version: 1, content: { tasks: [
      ...DEFAULT_PROCEDURE.tasks,
      ...Array.from({ length: 5 }, (_, index) => ({ ...DEFAULT_PROCEDURE.tasks[1], id: `EXTRA_${index}`, name: `Additional step ${index + 1}`, input: undefined })),
    ] } }
    if (path.endsWith('/capabilities')) json = { capabilities: [] }
    await route.fulfill({ json })
  })
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto('/project/objects/definitions/procedure')
  await expect(page.locator('.sidebar-project-tree .akis-tree-static-label[aria-label="Daily Load"]')).toBeVisible()
  const steps = page.getByRole('region', { name: 'Procedure steps', exact: true })
  const details = page.locator('.procedure-task-editor')
  await expect(steps).toBeVisible()
  await page.getByRole('tab', { name: 'Target Command', exact: true }).click()
  await page.getByRole('button', { name: 'Additional step 1', exact: true }).click()
  await expect(details.locator(':scope > header')).toContainText('Additional step 1')
  for (const height of [900, 768]) {
    await page.setViewportSize({ width: 1366, height })
    await expect(page.getByRole('tab', { name: 'Target Command', exact: true })).toHaveAttribute('aria-selected', 'true')
    await expect.poll(async () => (await details.boundingBox())!.y + (await details.boundingBox())!.height).toBeLessThanOrEqual(height)
    const top = (await steps.boundingBox())!, bottom = (await details.boundingBox())!
    expect(bottom.y - top.y - top.height).toBeGreaterThanOrEqual(10)
    await expect(details.getByRole('button', { name: 'Apply', exact: true })).toBeInViewport({ ratio: 1 })
    const sql = (await details.locator('.cm-editor').boundingBox())!
    expect(Math.min(sql.y + sql.height, bottom.y + bottom.height) - Math.max(sql.y, bottom.y)).toBeGreaterThan(80)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  const resize = page.getByRole('separator', { name: 'Explorer width' })
  await resize.press('End')
  await expect(resize).toHaveAttribute('aria-valuenow', '520')
  await resize.press('Home')
  await expect(resize).toHaveAttribute('aria-valuenow', '224')
  await resize.dblclick()
  await expect(resize).toHaveAttribute('aria-valuenow', '264')
  await page.screenshot({ path: `test-results/procedure-shell-${theme}.png` })
  for (const width of [768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(resize).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const apply = details.getByRole('button', { name: 'Apply', exact: true })
    await apply.scrollIntoViewIfNeeded()
    await expect(apply).toBeInViewport({ ratio: 1 })
    expect(await details.evaluate(root =>
      [root, ...root.querySelectorAll('.procedure-command-detail, .procedure-side, .procedure-side-context, .procedure-command, .procedure-sql-inline, .cm-editor, footer')].map(el => {
        const box = el.getBoundingClientRect(), style = getComputedStyle(el)
        return { className: el.className, x: box.x, y: box.y, width: box.width, height: box.height, scrollWidth: el.scrollWidth, scrollLeft: el.scrollLeft, scrollTop: el.scrollTop, display: style.display, minWidth: style.minWidth }
      }).filter(box => box.scrollWidth > box.width + 2))).toEqual([])
    const line = details.locator('.cm-line').first()
    await line.scrollIntoViewIfNeeded()
    await expect(line).toBeInViewport()
    const editorBox = (await details.locator('.cm-editor').boundingBox())!
    expect(editorBox.x).toBeGreaterThanOrEqual(0)
    expect(editorBox.x + editorBox.width).toBeLessThanOrEqual(width)
    await page.screenshot({ path: `test-results/procedure-shell-${theme}-${width}.png` })
  }
})
