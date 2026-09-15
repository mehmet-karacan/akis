import { expect, request, test } from '@playwright/test'
import { login } from './fixtures'

test('live SQL policy endpoint enforces authentication, source safety and bind discovery without execution', async ({ page }) => {
  const projectUuid = await login(page)
  const endpoint = `/api/v1/projects/${projectUuid}/sql/validate`
  const anonymous = await request.newContext({ baseURL: 'http://127.0.0.1:8080' })
  try {
    const denied = await anonymous.post(endpoint, { data: { command: 'SELECT 1 FROM DUAL', connectionRole: 'SOURCE' } })
    expect(denied.status()).toBe(401)
  } finally { await anonymous.dispose() }

  const username = process.env.AKIS_E2E_USERNAME!
  const password = process.env.AKIS_E2E_PASSWORD!
  const api = await request.newContext({
    baseURL: 'http://127.0.0.1:8080',
    extraHTTPHeaders: { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}` },
  })
  try {
    const checked = await api.post(endpoint, { data: {
      command: "SELECT q'[literal :ignored]' FROM NOT_A_REAL_TABLE WHERE ID = :id", connectionRole: 'SOURCE',
    } })
    expect(checked.status()).toBe(200)
    expect(await checked.json()).toMatchObject({ policyVersion: 1, riskClass: 'READ_ONLY', requiresApproval: false, binds: ['ID'] })
    // This endpoint classifies only. A nonexistent table must not trigger database execution.
    const destructive = await api.post(endpoint, { data: { command: 'DELETE FROM NOT_A_REAL_TABLE', connectionRole: 'TARGET' } })
    expect(destructive.status()).toBe(200)
    expect(await destructive.json()).toMatchObject({ riskClass: 'DESTRUCTIVE', requiresApproval: true })
    for (const command of ['DELETE FROM NOT_A_REAL_TABLE', 'SELECT ID, FROM NOT_A_REAL_TABLE']) {
      const rejected = await api.post(endpoint, { data: { command, connectionRole: 'SOURCE' } })
      expect(rejected.status()).toBe(422)
      expect(await rejected.json()).toMatchObject({ code: 'SQL_POLICY_REJECTED' })
    }
  } finally { await api.dispose() }
})
