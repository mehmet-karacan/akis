import { expect, request, test } from '@playwright/test'
import { expectHealthyScreen, expectNoHorizontalOverflow, login, navigateInApp } from './fixtures'

test.describe('AKIŞ critical browser journeys', () => {
  test('defaults to English and persists language and theme choices', async ({ page }) => {
    await page.goto('/login')
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
    await page.getByRole('button', { name: /language.*english/i }).click()
    await page.getByRole('menuitem', { name: /Turkish|Türkçe/i }).click()
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
    // External Basic authentication is intentionally rejected by the local
    // session-only security chain. The application exposes unauthenticated
    // API requests as 401; credentials are accepted only through local login.
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
      '/project/environments', '/project/models', '/project/team', '/project/publications',
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

  test('opens records from the shared action and keeps package authoring focused on the canvas', async ({ page }) => {
    await login(page)
    await page.getByRole('menuitem', { name: 'Project Objects', exact: true }).click()
    await expect(page).toHaveURL(/\/project\/objects$/)
    const packageToggle = page.getByRole('button', { name: /^(Packages|Paketler)$/ }).first()
    await packageToggle.click()
    const packageRecord = page.locator('.sidebar-project-tree span[title$="· Package"]').first()
    await expect(packageRecord.getByRole('button', { name: /^(Edit|Düzenle):/ })).toHaveCount(0)
    await packageRecord.locator('.akis-tree-static-label').dblclick()
    await expect(page.locator('.package-canvas')).toBeVisible()
    await expect(page.locator('.package-palette')).toHaveCount(0)
    await expect(page.locator('.package-accessible-list')).toHaveCount(0)
    await expect(page.locator('.package-editor-tabs')).toHaveCount(0)
    await expect(page.locator('select[aria-label="Project objects"]')).toHaveCount(0)
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
    await page.setViewportSize({ width: 1366, height: 768 })
    // Packages can contain different definition types. The first node in the
    // canonical package is an interface, so verify the interface editor rather
    // than assuming every package child is a procedure.
    await expect(page.locator('.mapping-editor')).toBeVisible()
    await expect(page.getByText('Source and target', { exact: true })).toBeVisible()
    await expect(page.getByText('Column mapping', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/package-child-definition.png', fullPage: true })
    const dimensions = await page.evaluate(() => {
      const panel = document.querySelector('.definition-editor-panel')?.getBoundingClientRect()
      const workbench = document.querySelector('.definition-workbench')?.getBoundingClientRect()
      return {
        ratio: panel && workbench ? panel.width / workbench.width : 0,
        scrollHeight: document.documentElement.scrollHeight,
        clientHeight: document.documentElement.clientHeight,
      }
    })
    expect(dimensions.ratio).toBeGreaterThan(.95)
    expect(dimensions.scrollHeight).toBeLessThanOrEqual(dimensions.clientHeight + 2)
  })

  test('opens a new procedure directly in the full editor instead of a dialog', async ({ page }) => {
    await login(page)
    await page.getByRole('menuitem', { name: 'Project Objects', exact: true }).click()
    await page.getByRole('button', { name: /Actions For Procedures/i }).first().click()
    await page.getByRole('menuitem', { name: 'Add Procedures', exact: true }).click()

    await expect(page).toHaveURL(/\/project\/objects$/)
    await expect(page.locator('[role="dialog"]')).toHaveCount(0)
    await expect(page.locator('.definition-new-editor')).toBeVisible()
    // A new definition is intentionally metadata-first: steps become
    // available after the definition is saved, without opening a dialog.
    await expect(page.getByText(/Save the definition first/i)).toBeVisible()
    await expect(page.getByRole('table', { name: 'Procedure steps' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: /^Step editor:/ })).toHaveCount(0)
  })

  test('opens available detail screens and reveals Oracle fields only after provider selection', async ({ page }) => {
    await login(page)

    await page.getByRole('menuitem', { name: /Connections|Bağlantılar/i }).click()
    await expect(page).toHaveURL(/\/project\/connections$/)
    await expectHealthyScreen(page)
    await page.getByRole('button', { name: /Add connection|Bağlantı Ekle/i }).click()
    const createForm = page.getByRole('dialog').locator('.topology-connection-form')
    await expect(createForm.locator('input[autocomplete="username"]')).toHaveCount(0)
    await createForm.getByRole('combobox').first().click()
    await page.getByRole('option', { name: 'Oracle', exact: true }).click()
    await expect(createForm.locator('input[autocomplete="username"]')).toBeVisible()
    await expect(createForm.locator('input[type="password"][autocomplete="new-password"]')).toBeVisible()

    await navigateInApp(page, '/project/connections')
    const connectionUuid = await page.locator('.ui-grid-record').first().getAttribute('data-connection-uuid')
    expect(connectionUuid, 'The baseline project must contain a connection').toBeTruthy()
    const connectionHref = `/project/connections/${connectionUuid}`
    await navigateInApp(page, connectionHref!, `/project/connections/${connectionUuid}`)
    await expectHealthyScreen(page)

    await page.getByRole('tab', { name: /Physical Schemas/i }).click()
    await expect(page.locator('.physical-schema-manager')).toBeVisible()
    await expect(page.getByRole('button', { name: /Add physical schema|Fiziksel şema ekle/i })).toBeVisible()

    await navigateInApp(page, '/project/environments')
    const environment = page.locator('.ui-grid-record').first()
    await expect(environment).toBeVisible()
    await environment.locator('button.ui-record-action:visible').click({ force: true })
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page).toHaveURL(/\/project\/environments$/)
    await expectHealthyScreen(page)
  })

  test('requires an environment and physical schema while creating a logical schema', async ({ page }) => {
    await login(page)
    await page.getByRole('menuitem', { name: /connections|bağlantılar/i }).click()
    await page.getByRole('link', { name: /logical schemas|mantıksal şemalar/i }).click()
    await page.getByRole('button', { name: /add logical schema|mantıksal şema ekle/i }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.locator('.topology-connection-form')).toBeVisible()
    const selects = dialog.getByRole('combobox')
    await expect(selects.first()).toBeVisible()
    const physicalSchema = selects.last()
    await expect(physicalSchema).toBeVisible()
    await physicalSchema.click()
    await expect(page.getByRole('option').first()).toBeVisible()
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

    await page.getByRole('menuitem', { name: 'Connections', exact: true }).click()
    await expect(page).toHaveURL(/\/project\/connections$/)
    const failure = page.locator('.ui-async-state.error')
    await expect(failure).toBeVisible()
    await page.unroute(catalogPattern)
    await failure.getByRole('button').click()
    await expect(failure).toHaveCount(0)
    await expect(page.locator('.ui-grid-surface, .ui-async-state.empty')).toBeVisible()
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
    const workspaceLinks = page.locator('.workspace-navigation [role="menuitem"]')
    await expect(workspaceLinks).toHaveCount(5)
    await expect(workspaceLinks.first()).toHaveText('Project')
    await expect(workspaceLinks.nth(2)).toHaveText('Run History')
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
    // Legacy aliases are deep links; exercise the browser/server entry path so
    // the redirect is verified independently of the in-app history helper.
    await page.goto('/project/topology')
    await expect(page).toHaveURL(/\/project\/connections(?:[?#].*)?$/)
    await page.goto('/project/runs')
    await expect(page).toHaveURL(/\/project\/operations(?:[?#].*)?$/)
  })
})
