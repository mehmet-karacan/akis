import { expect, test } from '@playwright/test'
import { login } from './fixtures'

test('keeps filters before the standard run summary and makes both reachable', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 700 })
  await login(page)
  await page.evaluate(async () => {
    const response = await fetch('/api/v1/projects')
    const projects = await response.json() as Array<{ uuid: string; code: string }>
    const project = projects.find(item => item.code === 'SKY')
    if (project) localStorage.setItem('akis.lastProjectUuid', project.uuid)
  })
  await page.goto('/project/operations')

  const filters = page.locator('.connection-management-panel form')
  const summary = page.locator('.run-summary-stack [aria-label]').first()
  await expect(filters).toBeVisible()
  await expect(summary).toBeVisible()
  await expect(summary.locator('.ui-summary-card')).toHaveCount(9)

  const layout = await page.locator('.execution-page').evaluate((container) => {
    const filtersElement = container.querySelector('.connection-management-panel form')!
    const summaryElement = container.querySelector('.run-summary-stack')!
    return {
      filtersBeforeSummary: Boolean(filtersElement.compareDocumentPosition(summaryElement) & Node.DOCUMENT_POSITION_FOLLOWING),
      overflowY: getComputedStyle(container).overflowY,
    }
  })
  expect(layout).toEqual({ filtersBeforeSummary: true, overflowY: 'auto' })

  await summary.scrollIntoViewIfNeeded()
  const summaryBox = await summary.boundingBox()
  expect(summaryBox).not.toBeNull()
  expect(summaryBox!.y).toBeGreaterThanOrEqual(0)
  expect(summaryBox!.y + summaryBox!.height).toBeLessThanOrEqual(700)
})

test('summarizes the same recent run scope shown by the default list', async ({ page }) => {
  const projectUuid = await login(page)
  const result = await page.evaluate(async (fallbackProjectUuid) => {
    const projectsResponse = await fetch('/api/v1/projects')
    const projects = await projectsResponse.json() as Array<{ uuid: string; code: string }>
    const selectedProjectUuid = projects.find(item => item.code === 'SKY')?.uuid ?? fallbackProjectUuid
    const [searchResponse, overviewResponse] = await Promise.all([
      fetch(`/api/v1/projects/${selectedProjectUuid}/runs/search?view=RECENT&page=0&size=1`),
      fetch(`/api/v1/projects/${selectedProjectUuid}/runs/overview`),
    ])
    return {
      searchStatus: searchResponse.status,
      overviewStatus: overviewResponse.status,
      search: await searchResponse.json() as { total: number },
      overview: await overviewResponse.json() as { totalRuns: number },
    }
  }, projectUuid)

  expect(result.searchStatus).toBe(200)
  expect(result.overviewStatus).toBe(200)
  expect(result.overview.totalRuns).toBe(result.search.total)
})
