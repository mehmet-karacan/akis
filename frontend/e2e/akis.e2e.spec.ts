import { expect, request, test } from '@playwright/test'
import { expectHealthyScreen, expectNoHorizontalOverflow, login, navigateInApp } from './fixtures'

test.describe('AKIŞ critical browser journeys', () => {
  test('defaults to English and persists language and theme choices', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
    await page.getByRole('button', { name: 'TR' }).click()
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr')
    await page.getByRole('button', { name: /tema|theme/i }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  })

  test('rejects invalid credentials without leaving the login screen', async ({ page }) => {
    const api = await request.newContext({
      baseURL: 'http://127.0.0.1:8080',
      extraHTTPHeaders: { Authorization: `Basic ${Buffer.from('invalid-e2e-user:invalid-e2e-password').toString('base64')}` },
    })
    const response = await api.get('/api/v1/projects')
    expect(response.status()).toBe(401)
    await api.dispose()

    await page.goto('/login')
    await page.route('**/api/v1/projects', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Unauthorized', detail: 'Invalid username or password.', status: 401 }),
      })
    })
    await page.locator('input[autocomplete="username"]').fill('invalid-e2e-user')
    await page.locator('input[autocomplete="current-password"]').fill('invalid-e2e-password')
    await page.locator('button[type="submit"]').click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('alert')).toBeVisible()
  })

  test('loads every primary workspace without a transport error', async ({ page }) => {
    const projectUuid = await login(page)
    const screens = [
      '', '/development', '/operations', '/connections', '/logical-schemas',
      '/environments', '/schema-bindings', '/models', '/team', '/publications',
    ]

    for (const suffix of screens) {
      await test.step(suffix || '/overview', async () => {
        await navigateInApp(page, `/projects/${projectUuid}${suffix}`)
        await expectHealthyScreen(page)
      })
    }
  })

  test('opens available detail screens and reveals Oracle fields only after provider selection', async ({ page }) => {
    const projectUuid = await login(page)

    await navigateInApp(page, `/projects/${projectUuid}/connections/new`)
    await expectHealthyScreen(page)
    const createForm = page.locator('.topology-connection-form')
    await expect(createForm.locator('input[autocomplete="username"]')).toHaveCount(0)
    await createForm.locator('select').first().selectOption('ORACLE')
    await expect(createForm.locator('input[autocomplete="username"]')).toBeVisible()
    await expect(createForm.locator('input[autocomplete="new-password"]')).toBeVisible()

    await navigateInApp(page, `/projects/${projectUuid}/connections`)
    const connectionHref = await page.locator('.connection-name-link').first().getAttribute('href')
    expect(connectionHref, 'The baseline project must contain a connection').toBeTruthy()
    await navigateInApp(page, connectionHref!)
    await expectHealthyScreen(page)

    const revisionHref = await page.locator('.revision-list a').first().getAttribute('href')
    expect(revisionHref, 'The baseline connection must contain a revision').toBeTruthy()
    await navigateInApp(page, revisionHref!)
    await expectHealthyScreen(page)

    await navigateInApp(page, connectionHref!)
    const physicalHref = await page.locator(`a[href="${connectionHref}/physical-schemas"]`).getAttribute('href')
    expect(physicalHref, 'The connection must expose physical-schema management').toBeTruthy()
    await navigateInApp(page, physicalHref!)
    await expectHealthyScreen(page)

    await navigateInApp(page, `/projects/${projectUuid}/environments`)
    const environmentHref = await page.locator('.schema-name-link').first().getAttribute('href')
    expect(environmentHref, 'The baseline project must contain an environment').toBeTruthy()
    await navigateInApp(page, environmentHref!)
    await expectHealthyScreen(page)
  })

  test('shows a forced connection-catalog failure and recovers through Retry', async ({ page }) => {
    const projectUuid = await login(page)
    const catalogPattern = '**/api/v1/projects/*/connections/catalog'
    await page.route(catalogPattern, async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Forced E2E failure', status: 500 }),
      })
    })

    await page.locator(`.sidebar a[href="/projects/${projectUuid}/connections"]`).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${projectUuid}/connections$`))
    const failure = page.locator('.ui-async-state.error')
    await expect(failure).toBeVisible()
    await page.unroute(catalogPattern)
    await failure.getByRole('button').click()
    await expect(failure).toHaveCount(0)
    await expect(page.locator('.connections-table, .ui-async-state.empty')).toBeVisible()
  })

  test('prioritises operations and hides write actions for an operation-only profile', async ({ page }) => {
    await page.route('**/api/v1/projects/*/access', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roles: ['OPERASYON'], permissions: ['CALISTIRMA_GORUNTULE'] }),
      })
    })
    const projectUuid = await login(page)
    const workspaceLinks = page.locator('.sidebar .workspace-navigation a')
    await expect(workspaceLinks).toHaveCount(3)
    await expect(workspaceLinks.first()).toHaveAttribute('href', `/projects/${projectUuid}/operations`)
    await navigateInApp(page, `/projects/${projectUuid}/connections`)
    await expect(page.locator(`a[href="/projects/${projectUuid}/connections/new"]`)).toHaveCount(0)
  })

  test('keeps the connections workspace inside narrow, tablet and desktop viewports', async ({ page }) => {
    const projectUuid = await login(page)
    await navigateInApp(page, `/projects/${projectUuid}/connections`)
    await expectHealthyScreen(page)

    for (const viewport of [
      { width: 320, height: 720 },
      { width: 768, height: 900 },
      { width: 1920, height: 1080 },
    ]) {
      await test.step(`${viewport.width}px`, async () => {
        await page.setViewportSize(viewport)
        await expectNoHorizontalOverflow(page)
      })
    }
  })

  test('redirects legacy topology and runs addresses to their canonical workspaces', async ({ page }) => {
    const projectUuid = await login(page)
    await navigateInApp(page, `/projects/${projectUuid}/topology`, `/projects/${projectUuid}/connections`)
    await navigateInApp(page, `/projects/${projectUuid}/runs`, `/projects/${projectUuid}/operations`)
  })
})
