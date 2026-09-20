import { expect, test } from '@playwright/test'

// Real UI and browser layout with synthetic responses, never business SQL.
for (const theme of ['light', 'dark']) {
  test(`KM templates and dynamic options remain editable in ${theme}`, async ({ page }) => {
    await page.addInitScript(mode => localStorage.setItem('akis.theme', mode), theme)
    await page.setViewportSize({ width: 1366, height: 900 })
    await page.route(/\/api\/v\d+\//, route => route.abort())
    await page.goto('/e2e/fixtures/authoring-workbench.html?editor=km')
    const source = page.getByRole('textbox', { name: 'KM Language Source' })
    await expect(source).toHaveValue(/AKIS_KM\/2\nMODUL IKM/)
    const first = page.getByRole('group', { name: 'Option 1', exact: true })
    await first.getByLabel('Display Name', { exact: true }).fill('Write Strategy')
    await first.getByLabel('Description', { exact: true }).fill('Choose the target operation')
    await first.getByLabel('Allowed Values', { exact: true }).fill('APPEND,MERGE,ATOMIC_DELETE_INSERT')
    await page.getByRole('group', { name: 'Option 4', exact: true }).getByLabel('Default', { exact: true }).fill('FULL(T) PARALLEL(4)')
    await expect(source).toHaveValue(/"FULL\(T\) PARALLEL\(4\)"/)
    await expect(first.getByLabel('Display Name', { exact: true })).toHaveValue('Write Strategy')
    await source.fill((await source.inputValue()).replace('ADIM HEDEFE_YAZ', 'SECENEK LIMIT INTEGER ISTEGE_BAGLI 9223372036854775807 YOK\nADIM HEDEFE_YAZ'))
    const integer = page.getByRole('group', { name: 'Option 5', exact: true }).getByRole('textbox', { name: 'Default', exact: true })
    await expect(integer).toHaveValue('9223372036854775807')
    await integer.fill('9223372036854775808')
    await integer.blur()
    await expect(integer).toHaveValue('9223372036854775808')
    await expect(integer).toHaveAttribute('aria-invalid', 'true')
    await integer.fill('9007199254740993')
    await expect(integer).toHaveAttribute('aria-invalid', 'false')
    await expect(source).toHaveValue(/INTEGER ISTEGE_BAGLI 9007199254740993 YOK/)
    const kind = page.getByRole('combobox', { name: 'Module Type and Template', exact: true })
    await kind.click()
    await page.getByText('LKM · Loading', { exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Replace Module Template' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).last().click()
    await expect(source).toHaveValue(/MODUL IKM/)
    await kind.click()
    await page.getByText('LKM · Loading', { exact: true }).click()
    await dialog.getByRole('button', { name: 'Apply Template' }).click()
    await expect(source).toHaveValue(/MODUL LKM/)
    await expect(page.getByRole('group', { name: /^Option \d/ })).toHaveCount(2)
    await expect(page.getByRole('group', { name: 'Option 1', exact: true }).getByLabel('Key', { exact: true })).toHaveValue('DISTINCT')
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    }
    await page.screenshot({ path: `test-results/km-options-${theme}.png`, fullPage: true })
  })

  test(`conditional KM diagnostic is readable in ${theme}`, async ({ page }) => {
    await page.addInitScript(mode => localStorage.setItem('akis.theme', mode), theme)
    await page.setViewportSize({ width: 1366, height: 900 })
    await page.route(/\/api\/v\d+\//, async route => {
      expect(new URL(route.request().url()).pathname.endsWith('/knowledge-language/validate')).toBe(true)
      expect(route.request().postDataJSON().source).toContain('EGER CHECK_ROWS')
      await route.fulfill({ json: { valid: true, runnable: false, line: 0, message: 'Syntax valid; no database execution', program: {
        steps: [{ id: 'QUALITY', site: 'STAGING', operation: 'CHECK_NOT_NULL', slot: 'WORK_SOURCE_1', line: 4 }],
        conditions: { QUALITY: 'CHECK_ROWS' },
      } } })
    })
    await page.goto('/e2e/fixtures/authoring-workbench.html?editor=km')
    await page.getByRole('textbox', { name: 'KM Language Source' }).fill('AKIS_KM/2\nMODUL CKM\nSECENEK CHECK_ROWS BOOLEAN ISTEGE_BAGLI true YOK\nADIM QUALITY STAGING CHECK_NOT_NULL WORK_SOURCE_1 EGER CHECK_ROWS')
    await page.getByRole('button', { name: 'Validate Language' }).click()
    await expect(page.getByRole('columnheader', { name: 'Run Condition' })).toBeVisible()
    await expect(page.getByRole('cell', { name: 'CHECK_ROWS = true', exact: true })).toBeVisible()
    await expect(page.getByText('Syntax valid; no database execution', { exact: true })).toBeVisible()
    for (const width of [1366, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    }
  })

  test(`variable tests show unsaved input, context, history and errors in ${theme}`, async ({ page }) => {
    await page.addInitScript(mode => localStorage.setItem('akis.theme', mode), theme)
    await page.setViewportSize({ width: 1366, height: 900 })
    let tests = 0
    let environmentActive = true
    await page.route(/\/api\/v\d+\//, async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      if (path.endsWith('/environments')) return route.fulfill({ json: [{ uuid: 'test', name: 'Test Environment', status: environmentActive ? 'AKTIF' : 'ARSIV' }] })
      if (path.endsWith('/value-tests') && request.method() === 'GET') return route.fulfill({ json: [] })
      expect(path.endsWith('/value-tests')).toBe(true)
      expect(request.method()).toBe('POST')
      expect(request.postDataJSON()).toEqual({ logicalSchemaUuid: 'logical', environmentUuid: 'test', dataType: 'DATE', query: 'SELECT SYSDATE - 2 FROM DUAL' })
      tests++
      return route.fulfill({ json: { id: tests, kind: 'TEST', success: tests === 1, value: tests === 1 ? '2026-09-15 12:00:00' : null, dataType: 'DATE', durationMs: 12, errorCode: tests === 1 ? null : 'ORACLE_28001', environment: 'Test Environment', logicalSchema: 'Logical Schema', createdAt: '2026-09-17T12:00:00Z' } })
    })
    await page.goto('/e2e/fixtures/authoring-workbench.html?editor=variable')
    await page.getByRole('textbox', { name: 'Variable Query' }).fill('   ')
    await page.getByRole('combobox', { name: 'Test Environment' }).click()
    await page.getByRole('option', { name: 'Test Environment' }).click()
    await expect(page.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
    await expect(page.getByText(/No sample query is executed automatically/)).toBeVisible()
    expect(tests).toBe(0)
    await page.getByRole('textbox', { name: 'Variable Query' }).fill('SELECT SYSDATE - 2 FROM DUAL')
    await page.getByRole('button', { name: 'Run and Test' }).click()
    await expect(page.getByText('2026-09-15 12:00:00', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Logical Schema / Test Environment', { exact: true })).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: 'Passed' })).toHaveCount(1)
    await page.getByRole('button', { name: 'Run and Test' }).click()
    await expect(page.getByRole('row').filter({ hasText: 'Failed' })).toHaveCount(1)
    await expect(page.getByRole('row').filter({ hasText: 'Passed' })).toHaveCount(1)
    await expect(page.getByText(/ORACLE_28001/).first()).toBeVisible()
    const toast = page.locator('.ui-feedback-toast')
    await expect(toast).toBeVisible()
    await expect.poll(() => toast.evaluate(element => {
      const frame = element.getBoundingClientRect()
      const message = element.querySelector('.ant-notification-notice-title')?.getBoundingClientRect()
      return !!message && message.top >= frame.top && message.bottom <= frame.bottom && message.right <= frame.right
    })).toBe(true)
    await page.getByRole('textbox', { name: 'Variable Query' }).fill('SELECT SYSDATE - 3 FROM DUAL')
    await expect(page.getByText(/result below belongs to the previous test inputs/)).toBeVisible()
    expect(tests).toBe(2)
    await page.screenshot({ path: `test-results/variable-tests-${theme}.png`, fullPage: true })
    environmentActive = false
    await page.getByRole('button', { name: 'Refresh History' }).click()
    await expect(page.getByText(/selected environment is no longer active/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
    expect(tests).toBe(2)
  })
}
