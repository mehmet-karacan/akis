import { expect, request, test } from '@playwright/test'
import { login } from './fixtures'

test('live SQL policy endpoint enforces authentication, source safety and bind discovery without execution', async ({ page }) => {
  const projectUuid = await login(page)
  const endpoint = `/api/v1/projects/${projectUuid}/sql/validate`
  const anonymous = await request.newContext({ baseURL: 'http://127.0.0.1:8080' })
  try {
    const denied = await anonymous.post(endpoint, { data: { command: 'SELECT 1 FROM DUAL', connectionRole: 'SOURCE' } })
    // Anonymous mutating requests are rejected by the CSRF boundary before
    // authentication is evaluated. The browser client obtains the token first.
    expect(denied.status()).toBe(403)
  } finally { await anonymous.dispose() }

  const username = process.env.AKIS_E2E_USERNAME!
  const password = process.env.AKIS_E2E_PASSWORD!
  const api = await request.newContext({
    baseURL: 'http://127.0.0.1:8080',
  })
  try {
    const csrf = await api.get('/api/v1/auth/csrf')
    const csrfToken = (await csrf.json()).token as string
    const loginResponse = await api.post('/api/v1/auth/login', {
      headers: { 'X-XSRF-TOKEN': csrfToken },
      data: { kullaniciKodu: username, parola: password },
    })
    expect(loginResponse.status()).toBe(201)
    const headers = { 'X-XSRF-TOKEN': csrfToken }
    const checked = await api.post(endpoint, { headers, data: {
      command: "SELECT q'[literal :ignored]' FROM NOT_A_REAL_TABLE WHERE ID = :id", connectionRole: 'SOURCE',
    } })
    expect(checked.status()).toBe(200)
    expect(await checked.json()).toMatchObject({ policyVersion: 1, riskClass: 'READ_ONLY', requiresApproval: false, binds: ['ID'] })
    // This endpoint classifies only. A nonexistent table must not trigger database execution.
    const destructive = await api.post(endpoint, { headers, data: { command: 'DELETE FROM NOT_A_REAL_TABLE', connectionRole: 'TARGET' } })
    expect(destructive.status()).toBe(200)
    expect(await destructive.json()).toMatchObject({ riskClass: 'DESTRUCTIVE', requiresApproval: true })
    for (const command of ['DELETE FROM NOT_A_REAL_TABLE', 'SELECT ID, FROM NOT_A_REAL_TABLE']) {
      const rejected = await api.post(endpoint, { headers, data: { command, connectionRole: 'SOURCE' } })
      expect(rejected.status()).toBe(422)
      expect(await rejected.json()).toMatchObject({ code: 'SQL_POLICY_REJECTED' })
    }
  } finally { await api.dispose() }
})
