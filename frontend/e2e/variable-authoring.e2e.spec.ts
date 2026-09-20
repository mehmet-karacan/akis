import { expect, test } from '@playwright/test'
import { login } from './fixtures'

// Uses a saved baseline variable, but does not save or execute a business procedure.
test('loads a real saved refresh variable into an unsaved procedure without losing its query', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await login(page)
  await page.goto('/project/objects?createType=PROCEDURE')
  await expect(page.locator('.definition-new-editor')).toBeVisible()
  await page.getByRole('tab', { name: 'Source Command', exact: true }).click()
  const editor = page.locator('.procedure-side--source .cm-content')
  await editor.click()
  await page.keyboard.press('Control+A')
  await page.keyboard.type('SELECT ID FROM HAKEDIS_TIPI WHERE TANIMLAMA_ZAMANI >= @')
  await expect(page.getByRole('option').first()).toBeVisible()
  const draftResponse = page.waitForResponse((response) => /\/definitions\/[^/]+\/draft$/.test(response.url()) && response.request().method() === 'GET')
  const selectedVariable = (await page.getByRole('option').first().innerText()).replace(/^@/, '').trim()
  await page.getByRole('option').first().click()
  await page.getByRole('button', { name: 'Apply', exact: true }).click()
  const response = await draftResponse
  const id = response.url().match(/\/definitions\/([^/]+)\/draft$/)![1]
  expect(response.status()).toBe(200)
  const draft = await response.json()
  expect(draft.content.valueSource).toBe('REFRESH_QUERY')
  expect(draft.content.query).toMatch(/^SELECT\s+SYSDATE\s*-\s*1\s+FROM\s+DUAL$/i)
  await expect(page.locator('.procedure-side--source .cm-content')).toContainText(`@${selectedVariable}`)
  expect(errors).toEqual([])
  await page.goto(`/project/objects/definitions/${id}`)
  await expect(page.getByLabel('Logical Schema', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Value History', exact: true })).toBeVisible()
  await expect(page.getByText('Value history could not be loaded.')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/variable-schema-history.png', fullPage: true })
})
