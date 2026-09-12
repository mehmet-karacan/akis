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
    await login(page)
    const screens = [
      '/project', '/project/objects', '/project/operations', '/project/connections', '/project/logical-schemas',
      '/project/environments', '/project/schema-bindings', '/project/models', '/project/team', '/project/publications',
    ]

    for (const path of screens) {
      await test.step(path, async () => {
        await navigateInApp(page, path)
        await expectHealthyScreen(page)
      })
    }
  })

  test('keeps the signed-in session and clean project URL after reload', async ({ page }) => {
    await login(page)
    await expect(page).toHaveURL(/\/project$/)
    await expect(page.locator('.sidebar')).toHaveCount(0)

    await page.reload()

    await expect(page).toHaveURL(/\/project$/)
    await expect(page.locator('input[autocomplete="current-password"]')).toHaveCount(0)
    await expect(page.locator('.workspace-navigation')).toBeVisible()
    await expect(page.locator('.sidebar')).toHaveCount(0)
  })

  test('keeps package authoring focused on the canvas and opens linked objects on double click', async ({ page }) => {
    await login(page)
    await page.locator('.workspace-navigation a[href="/project/objects"]').click()
    await expect(page).toHaveURL(/\/project\/objects$/)
    await expect(page.locator('.sidebar-type-cluster').filter({ hasText: 'Procedures' }).first()).toBeVisible()
    const packageCluster = page.locator('.sidebar-type-cluster').filter({ hasText: 'Packages' }).first()
    await expect(packageCluster).toBeVisible()
    await packageCluster.locator('.sidebar-folder-row').click()
    const packageRow = page.locator('.sidebar-object-row').filter({ has: page.locator('.sidebar-object-icon--package') }).first()
    await expect(packageRow).toBeVisible()
    await packageRow.locator('.sidebar-object').click()
    await expect(page.locator('.package-canvas')).toBeVisible()
    await expect(page.locator('.package-palette')).toHaveCount(0)
    await expect(page.locator('.package-accessible-list')).toHaveCount(0)
    await expect(page.locator('.package-editor-tabs')).toHaveCount(0)
    await expect(page.locator('select[aria-label="Project objects"]')).toHaveCount(0)
    await expect(page.getByRole('tab', { name: /immutable versions/i })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /move definition|reload draft/i })).toHaveCount(0)
    await expect(page.locator('.package-properties')).toHaveCount(0)

    await page.getByRole('button', { name: 'Add object' }).click()
    await expect(page.locator('.package-component-picker')).toBeVisible()
    await expect(page.locator('.package-component-picker input')).toHaveCount(0)
    await page.locator('.package-component-picker').getByRole('button', { name: 'Close' }).click()
    await page.locator('.react-flow__pane').click({ button: 'right', position: { x: 900, y: 400 } })
    await expect(page.locator('.package-component-picker')).toBeVisible()
    await page.locator('.package-component-picker').getByRole('button', { name: 'Close' }).click()

    const firstNode = page.locator('.react-flow__node').first()
    await expect(firstNode).toBeVisible()
    await firstNode.click({ force: true })
    await expect(page.locator('.package-properties')).toBeVisible()
    const packageUrl = page.url()
    await firstNode.dblclick({ force: true })
    await expect(page).not.toHaveURL(packageUrl)
    await expect(page).toHaveURL(/\/project\/objects\/definitions\//)
    await page.setViewportSize({ width: 2560, height: 1080 })
    await expect(page.locator('.procedure-task').first()).toBeVisible()
    await page.locator('.procedure-task-select').first().click()
    await expect(page.getByRole('tab', { name: /Source Command|Kaynak Komutu/ })).toBeVisible()
    await expect(page.getByRole('tab', { name: /Target Command|Hedef Komutu/ })).toBeVisible()
    await page.getByRole('tab', { name: /Source Command|Kaynak Komutu/ }).click()
    await expect(page.locator('.procedure-side--source')).toBeVisible()
    await page.getByRole('tab', { name: /Target Command|Hedef Komutu/ }).click()
    await expect(page.locator('.procedure-side--target')).toBeVisible()
    await expect(page.getByText(/Step ID|Adım Kimliği/)).toHaveCount(0)
    await expect(page.getByText(/Task type|Görev Türü|Connection role|Bağlantı Rolü|Risk class|Risk Sınıfı|On error|Hata Durumunda/)).toHaveCount(0)
    await expect(page.getByRole('tab', { name: /Data Bindings|Veri Bağları/ })).toHaveCount(0)
    await expect(page.getByText(/Resolved execution context|Çözümlenen Çalışma Bağlamı/)).toHaveCount(0)
    await expect(page.locator('.procedure-side select').first().locator('option').first()).toHaveText(/Not selected|Seçilmedi/)
    const dimensions = await page.evaluate(() => {
      const panel = document.querySelector('.definition-editor-panel')?.getBoundingClientRect()
      const workbench = document.querySelector('.definition-workbench')?.getBoundingClientRect()
      return { ratio: panel && workbench ? panel.width / workbench.width : 0, pageFits: document.documentElement.scrollHeight <= document.documentElement.clientHeight + 2 }
    })
    expect(dimensions.ratio).toBeGreaterThan(.95)
    expect(dimensions.pageFits).toBe(true)
  })

  test('opens available detail screens and reveals Oracle fields only after provider selection', async ({ page }) => {
    await login(page)

    await navigateInApp(page, '/project/connections/new')
    await expectHealthyScreen(page)
    const createForm = page.locator('.topology-connection-form')
    await expect(createForm.locator('input[autocomplete="username"]')).toHaveCount(0)
    await createForm.locator('select').first().selectOption('ORACLE')
    await expect(createForm.locator('input[autocomplete="username"]')).toBeVisible()
    await expect(createForm.locator('input[autocomplete="new-password"]')).toBeVisible()

    await navigateInApp(page, '/project/connections')
    const connectionHref = await page.locator('.connection-name-link').first().getAttribute('href')
    expect(connectionHref, 'The baseline project must contain a connection').toBeTruthy()
    const connectionUuid = connectionHref!.split('/').at(-1)!
    await navigateInApp(page, connectionHref!, `/project/connections/${connectionUuid}`)
    await expectHealthyScreen(page)

    const physicalHref = await page.locator(`a[href="${connectionHref}/physical-schemas"]`).getAttribute('href')
    expect(physicalHref, 'The connection must expose physical-schema management').toBeTruthy()
    await navigateInApp(page, physicalHref!, `/project/connections/${connectionUuid}/physical-schemas`)
    await expectHealthyScreen(page)

    await navigateInApp(page, '/project/environments')
    const environmentHref = await page.locator('.schema-name-link').first().getAttribute('href')
    expect(environmentHref, 'The baseline project must contain an environment').toBeTruthy()
    const environmentUuid = environmentHref!.split('/').at(-1)!
    await navigateInApp(page, environmentHref!, `/project/environments/${environmentUuid}`)
    await expectHealthyScreen(page)
  })

  test('requires an environment and physical schema while creating a logical schema', async ({ page }) => {
    await login(page)
    await page.getByRole('tab', { name: /connections|bağlantılar/i }).click()
    await page.getByRole('link', { name: /logical schemas|mantıksal şemalar/i }).click()
    await page.getByRole('button', { name: /add logical schema|mantıksal şema ekle/i }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.getByLabel(/environment|ortam/i)).toBeVisible()
    const physicalSchema = dialog.getByLabel(/physical schema|fiziksel şema/i)
    await expect(physicalSchema).toBeVisible()
    await expect(physicalSchema.locator('option')).not.toHaveCount(1)
    await expect(dialog.getByText(/revision|revizyon/i)).toHaveCount(0)
  })

  test('shows a forced connection-catalog failure and recovers through Retry', async ({ page }) => {
    await login(page)
    const catalogPattern = '**/api/v1/projects/*/connections/catalog'
    await page.route(catalogPattern, async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: 'Forced E2E failure', status: 500 }),
      })
    })

    await page.locator('.workspace-navigation a[href="/project/connections"]').click()
    await expect(page).toHaveURL(/\/project\/connections$/)
    const failure = page.locator('.ui-async-state.error')
    await expect(failure).toBeVisible()
    await page.unroute(catalogPattern)
    await failure.getByRole('button').click()
    await expect(failure).toHaveCount(0)
    await expect(page.locator('.connections-table, .ui-async-state.empty')).toBeVisible()
  })

  test('keeps project first and hides write actions for an operation-only profile', async ({ page }) => {
    await page.route('**/api/v1/projects/*/access', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roles: ['OPERASYON'], permissions: ['CALISTIRMA_GORUNTULE'] }),
      })
    })
    await login(page)
    const workspaceLinks = page.locator('.workspace-navigation a')
    await expect(workspaceLinks).toHaveCount(4)
    await expect(workspaceLinks.first()).toHaveAttribute('href', '/project')
    await expect(workspaceLinks.nth(2)).toHaveAttribute('href', '/project/operations')
    await navigateInApp(page, '/project/connections')
    await expect(page.locator('a[href="/project/connections/new"]')).toHaveCount(0)
  })

  test('keeps the connections workspace inside narrow, tablet and desktop viewports', async ({ page }) => {
    await login(page)
    await navigateInApp(page, '/project/connections')
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
    await login(page)
    await navigateInApp(page, '/project/topology', '/project/connections')
    await navigateInApp(page, '/project/runs', '/project/operations')
  })
})
