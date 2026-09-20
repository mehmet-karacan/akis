import { expect, test } from '@playwright/test'
import { login, expectNoHorizontalOverflow } from './fixtures'

test('Ant Design login and workspace surfaces fit both themes and viewport sizes', async ({ page }) => {
  test.setTimeout(90_000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  for (const theme of ['light', 'dark']) {
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/login')
      await page.evaluate(value => localStorage.setItem('akis.theme', value), theme)
      await page.reload()
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      await expect(page.locator('.login-submit.ant-btn')).toBeVisible()
      await expect(page.locator('input[autocomplete="current-password"]')).toBeVisible()
      await expectNoHorizontalOverflow(page)
      await page.screenshot({ path: `test-results/ant-login-${theme}-${width}.png`, fullPage: true })
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await login(page)
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => localStorage.setItem('akis.theme', value), theme)
    for (const path of ['connections', 'environments', 'operations', 'objects/definitions/8be2f931-3734-4f16-ba4f-2604d06d8c9c']) {
      await page.goto(`/project/${path}`)
      await expect(page.locator('#main-content')).toBeVisible()
      await expect(page.getByText('No project selected', { exact: true })).toHaveCount(0)
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      await expect(page.locator('.ui-async-state.loading')).toHaveCount(0)
      if (path.startsWith('objects')) {
        await expect(page.locator('.procedure-editor')).toBeVisible()
        await expect(page.locator('.procedure-task-actions button').first()).toBeVisible()
        await page.getByRole('tab', { name: 'General', exact: true }).click()
        const name = await page.getByRole('textbox', { name: 'Step name', exact: true }).boundingBox()
        const counter = await page.locator('.procedure-general-properties .ant-select').boundingBox()
        expect(name).not.toBeNull()
        expect(counter).not.toBeNull()
        expect(Math.abs(name!.y - counter!.y)).toBeLessThanOrEqual(2)
        expect(Math.abs(name!.height - counter!.height)).toBeLessThanOrEqual(2)
        expect(Math.abs(name!.width - counter!.width)).toBeLessThanOrEqual(2)
        await page.getByRole('tab', { name: 'Target Command', exact: true }).click()
        await expect(page.locator('.cm-editor')).toBeVisible()
      }
      await expectNoHorizontalOverflow(page)
      await page.screenshot({ path: `test-results/ant-${path.split('/')[0]}-${theme}.png`, fullPage: true })
      if (path === 'connections') {
        await page.locator('.ui-grid-record').first().getByRole('button', { name: /^View:/ }).click()
        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()
        await expect(dialog.locator('button[type="submit"].ant-btn-primary')).toBeVisible()
        await page.screenshot({ path: `test-results/ant-connection-detail-${theme}.png` })
        await page.keyboard.press('Escape')
        await expect(dialog).toHaveCount(0)
      }
      if (path === 'operations') {
        const details = page.getByRole('button', { name: 'View Details' })
        if (await details.count()) {
          await details.first().click()
          const dialog = page.getByRole('dialog')
          await expect(dialog).toBeVisible()
          await expect(dialog.getByText('Attempt', { exact: true })).toHaveCount(0)
          await expect(dialog.getByText('Technical identifiers', { exact: true })).toHaveCount(0)
          await expect(dialog.getByText('Evidence', { exact: true })).toHaveCount(0)
          await page.screenshot({ path: `test-results/ant-run-detail-${theme}.png` })
          await page.keyboard.press('Escape')
          await expect(dialog).toHaveCount(0)
        } else await expect(page.getByText('No runs have been requested for this project.')).toBeVisible()
      }
    }
  }
  expect(errors).toEqual([])
})
