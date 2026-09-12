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
  it('creates an Oracle definition and its first endpoint atomically', async () => {
    const fetchMock = mockResponse({ connection: { uuid: 'connection' }, initialVersion: { uuid: 'version' } })
    const initialVersion = {
      mode: 'JDBC' as const,
      jdbc: {
        host: 'oracle.example', port: 1521,
        connectIdentifier: { type: 'SERVICE_NAME' as const, value: 'ORCL' },
        transport: 'TCP' as const,
      },
      policyVersion: 2 as const,
      executionPolicy: {
        connectTimeoutMs: 10000, readTimeoutMs: 30000,
        networkTimeoutMs: 30000, queryTimeoutSeconds: 300,
      },
    }

    await topologyApi.createOracleConnection('project id', {
      code: 'ORACLE_MAIN', name: 'Oracle Main', initialVersion,
      credentials: { username: 'reader', password: 'local-secret' },
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v2/projects/project%20id/connections')
    expect(JSON.parse(String(init.body))).toEqual({
      code: 'ORACLE_MAIN', name: 'Oracle Main', initialVersion,
      credentials: { username: 'reader', password: 'local-secret' },
    })
  })

  it('tests unsaved Oracle fields without creating metadata', async () => {
    const fetchMock = mockResponse({ connected: true, oracle19cCompatible: true })
    const draft = {
      mode: 'JDBC' as const,
      jdbc: { host: '10.0.0.1', port: 1521, connectIdentifier: { type: 'SID' as const, value: 'ORCL' }, transport: 'TCP' as const },
      credentials: { username: 'reader', password: 'local-secret' },
      policyVersion: 2 as const,
      executionPolicy: { connectTimeoutMs: 10000, readTimeoutMs: 30000, networkTimeoutMs: 30000, queryTimeoutSeconds: 300 },
    }

    await topologyApi.testOracleDraftConnection('project id', draft)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v2/projects/project%20id/connections/test')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(draft)
  })

  it('creates Oracle connection versions through the isolated V2 contract', async () => {
    const fetchMock = mockResponse({ mode: 'JNDI' })
    const body = {
      mode: 'JNDI' as const,
      jndi: { name: 'java:comp/env/jdbc/OracleMain' },
      policyVersion: 2 as const,
      executionPolicy: {
        connectTimeoutMs: 10000, readTimeoutMs: 30000,
        networkTimeoutMs: 30000, queryTimeoutSeconds: 300,
      },
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

  it('captures a server-produced schema snapshot without sending client metadata', async () => {
    const fetchMock = mockResponse({ uuid: 'snapshot', serverProduced: true })

    await topologyApi.captureOracleSchemaSnapshot(
      'project id',
      'connection/id',
      'version id',
      'physical schema',
      'data/object',
    )

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v2/projects/project%20id/connections/connection%2Fid/versions/version%20id/physical-schemas/physical%20schema/data-objects/data%2Fobject/schema-snapshots:discover')
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
  })

  it('defaults module labels to English and switches to Turkish explicitly', () => {
    expect(getTopologyCopy('de').connections).toBe('Connections')
    expect(getTopologyCopy('tr-TR').connections).toBe('Bağlantılar')
  })
})
