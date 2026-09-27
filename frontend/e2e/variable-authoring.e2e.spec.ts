import { expect, test } from '@playwright/test'
import { login } from './fixtures'

test('new procedures require metadata before authoring steps and variable references', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await login(page)
  await page.goto('/project/objects?createType=PROCEDURE')
  await expect(page.locator('.definition-new-editor')).toBeVisible()
  await expect(page.getByText(/Save the definition first|Önce tanımı kaydedin/i)).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Source Command', exact: true })).toHaveCount(0)
  await expect(page.getByRole('table', { name: /Procedure steps|Prosedür adımları/i })).toHaveCount(0)
  await expect(page.locator('.procedure-side--source')).toHaveCount(0)
  expect(errors).toEqual([])
})
