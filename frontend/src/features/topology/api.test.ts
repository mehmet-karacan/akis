import { afterEach, describe, expect, it, vi } from 'vitest'
import { topologyApi } from './api'
import { getTopologyCopy } from './copy'

afterEach(() => vi.unstubAllGlobals())

function mockResponse(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
  })
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })
  return fetchMock
}

describe('topology API contracts', () => {
  it('creates Oracle connection versions through the isolated V2 contract', async () => {
    const fetchMock = mockResponse({ mode: 'JNDI' })
    const body = {
      mode: 'JNDI' as const,
      jndi: { name: 'java:comp/env/jdbc/OracleMain' },
      policyVersion: 2 as const,
      executionPolicy: {},
    }

    await topologyApi.createVersion('project id', 'connection/id', body)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v2/projects/project%20id/connections/connection%2Fid/versions')
    expect(JSON.parse(String(init.body))).toEqual(body)
  })

  it('persists Oracle connection tests through the V2 lifecycle endpoint', async () => {
    const fetchMock = mockResponse({ outcome: 'PASSED' })

    await topologyApi.testConnectionVersion('project id', 'connection/id', 'version id')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v2/projects/project%20id/connections/connection%2Fid/versions/version%20id/tests')
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
  })

  it('loads bounded test evidence and activates with optimistic state', async () => {
    const fetchMock = mockResponse([])

    await topologyApi.listConnectionVersionTests('project', 'connection', 'version', 5)
    await topologyApi.activateConnectionVersion('project', 'connection', 'version', {
      testUuid: 'test-uuid',
      expectedStateVersion: 3,
    })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v2/projects/project/connections/connection/versions/version/tests?limit=5')
    const [activatePath, activateInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(activatePath).toBe('/api/v2/projects/project/connections/connection/versions/version/activate')
    expect(activateInit.method).toBe('POST')
    expect(JSON.parse(String(activateInit.body))).toEqual({ testUuid: 'test-uuid', expectedStateVersion: 3 })
  })

  it('sends discovery filters to the physical-schema discovery endpoint', async () => {
    const fetchMock = mockResponse({ tables: [] })

    await topologyApi.discoverOracle('project', 'connection', 'version', 'physical/schema', {
      tableName: 'CUSTOMER',
      limit: 25,
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project/connections/connection/versions/version/physical-schemas/physical%2Fschema/discover')
    expect(JSON.parse(String(init.body))).toEqual({ tableName: 'CUSTOMER', limit: 25 })
  })

  it('defaults module labels to English and switches to Turkish explicitly', () => {
    expect(getTopologyCopy('de').connections).toBe('Connections')
    expect(getTopologyCopy('tr-TR').connections).toBe('Bağlantılar')
  })
})
