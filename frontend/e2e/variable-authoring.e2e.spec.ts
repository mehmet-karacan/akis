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
  const variables = page.getByLabel('Defined Variables', { exact: true })
  await variables.click()
  await expect(page.getByRole('option').nth(1)).toBeVisible()
  const draftResponse = page.waitForResponse((response) => /\/definitions\/[^/]+\/draft$/.test(response.url()) && response.request().method() === 'GET')
  await page.getByRole('option').nth(1).click()
  const response = await draftResponse
  const id = response.url().match(/\/definitions\/([^/]+)\/draft$/)![1]
  expect(response.status()).toBe(200)
  const draft = await response.json()
  expect(draft.content.valueSource).toBe('REFRESH_QUERY')
  expect(draft.content.query).toMatch(/^SELECT\s+SYSDATE\s*-\s*1\s+FROM\s+DUAL$/i)
  const chip = page.locator('.procedure-variable-chip').first()
  await expect(chip).toBeVisible()
  await expect(chip).toHaveAttribute('title', draft.content.query)
  const bind = (await chip.locator('code').innerText()).trim()
  expect(bind).toMatch(/^:[A-Z][A-Z0-9_]*$/)
  await page.locator('.procedure-side--source .cm-content').fill(`SELECT ID FROM HAKEDIS_TIPI WHERE TANIMLAMA_ZAMANI >= ${bind}`)
  await expect(chip).toBeVisible()
  await expect(page.locator('.procedure-side--source .cm-content')).toContainText(bind)
  expect(errors).toEqual([])
  await page.goto(`/project/objects/definitions/${id}`)
  await expect(page.getByLabel('Logical Schema', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Value History', exact: true })).toBeVisible()
  await expect(page.getByText('Value history could not be loaded.')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/variable-schema-history.png', fullPage: true })
})
