import { afterEach, describe, expect, it, vi } from 'vitest'
import { topologyApi, type ConnectionRequest } from './api'
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

const oracleRequest: ConnectionRequest = {
  code: 'ORACLE_MAIN', name: 'Oracle Main', databaseType: 'ORACLE', mode: 'JDBC',
  host: '10.0.0.1', port: 1521, serviceName: 'ORCL', username: 'reader', password: 'local-secret',
}

describe('topology API contracts', () => {
  it('moves only the catalog folder with optimistic concurrency', async () => {
    const fetchMock = mockResponse({ uuid: 'object', version: 8 })
    await topologyApi.moveDataObject('p id', 'm id', 'o id', { submodelUuid: null, expectedVersion: 7 })
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/p%20id/models/m%20id/data-objects/o%20id/folder')
    expect(init.method).toBe('PATCH')
    expect(JSON.parse(String(init.body))).toEqual({ submodelUuid: null, expectedVersion: 7 })
  })
  it('loads the connection catalog through one summary endpoint', async () => {
    const fetchMock = mockResponse([])
    await topologyApi.listConnectionCatalog('project id')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project%20id/connections/catalog')
  })

  it('tests unsaved connection fields without creating metadata', async () => {
    const fetchMock = mockResponse({ connected: true })

    await topologyApi.testDraftConnection('project id', oracleRequest)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/connections/test')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(oracleRequest)
  })

  it('creates a connection with its endpoint and credentials in one flat request', async () => {
    const fetchMock = mockResponse({ uuid: 'connection' })

    await topologyApi.createConnection('project id', oracleRequest)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/connections')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(oracleRequest)
  })

  it('updates a connection in place and deletes it without a version token', async () => {
    const fetchMock = mockResponse({ uuid: 'connection/id' })
    const withoutPassword: ConnectionRequest = { ...oracleRequest, password: undefined }

    await topologyApi.updateConnection('project id', 'connection/id', withoutPassword)
    await topologyApi.deleteConnection('project id', 'connection/id')

    const [updatePath, updateInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(updatePath).toBe('/api/v1/projects/project%20id/connections/connection%2Fid')
    expect(updateInit.method).toBe('PATCH')
    expect(JSON.parse(String(updateInit.body))).toEqual(withoutPassword)
    const [deletePath, deleteInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(deletePath).toBe('/api/v1/projects/project%20id/connections/connection%2Fid')
    expect(deleteInit.method).toBe('DELETE')
  })

  it('records and lists connection tests on the connection itself', async () => {
    const fetchMock = mockResponse({ outcome: 'PASSED' })

    await topologyApi.testConnection('project id', 'connection/id')
    await topologyApi.listConnectionTests('project id', 'connection/id', 5)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/connections/connection%2Fid/tests')
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/projects/project%20id/connections/connection%2Fid/tests?limit=5')
  })

  it('updates a schema mapping by its own identity', async () => {
    const fetchMock = mockResponse({ uuid: 'binding' })
    await topologyApi.updateBinding('project', 'binding/id', {
      logicalSchemaUuid: 'logical', environmentUuid: 'environment', physicalSchemaUuid: 'physical',
    })
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project/schema-bindings/binding%2Fid')
    expect(init.method).toBe('PATCH')
    expect(JSON.parse(String(init.body))).toEqual({
      logicalSchemaUuid: 'logical', environmentUuid: 'environment', physicalSchemaUuid: 'physical',
    })
  })

  it('sends discovery filters to the physical-schema discovery endpoint', async () => {
    const fetchMock = mockResponse({ tables: [] })

    await topologyApi.discoverOracle('project', 'connection', 'physical/schema', {
      tableName: 'CUSTOMER',
      limit: 25,
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project/connections/connection/physical-schemas/physical%2Fschema/discover')
    expect(JSON.parse(String(init.body))).toEqual({ tableName: 'CUSTOMER', limit: 25 })
  })

  it('creates an environment with only its name and code', async () => {
    const fetchMock = mockResponse({ uuid: 'environment' })

    await topologyApi.createEnvironment('project id', { code: 'TEST', name: 'Test Ortamı' })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/environments')
    expect(JSON.parse(String(init.body))).toEqual({ code: 'TEST', name: 'Test Ortamı' })
  })

  it('loads database schemas and creates a physical schema with its work schema and prefixes', async () => {
    const fetchMock = mockResponse(['TTBP', 'INNOVA_ODI'])

    await topologyApi.listDatabaseSchemas('project', 'connection')
    await topologyApi.createPhysicalSchema('project', { connectionUuid: 'connection', schemaName: 'TTBP', workSchemaName: 'TTBP_WORK', loadingPrefix: 'C$_' })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project/connections/connection/schemas')
    const [createPath, createInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(createPath).toBe('/api/v1/projects/project/physical-schemas')
    expect(JSON.parse(String(createInit.body))).toEqual({ connectionUuid: 'connection', schemaName: 'TTBP', workSchemaName: 'TTBP_WORK', loadingPrefix: 'C$_' })
  })

  it('updates and deletes a physical schema by its identity', async () => {
    const fetchMock = mockResponse({ uuid: 'physical/schema' })

    await topologyApi.updatePhysicalSchema('project id', 'physical/schema', { name: 'Stage', schemaName: 'STAGE_OWNER' })
    await topologyApi.deletePhysicalSchema('project id', 'physical/schema')

    const [updatePath, updateInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(updatePath).toBe('/api/v1/projects/project%20id/physical-schemas/physical%2Fschema')
    expect(updateInit.method).toBe('PATCH')
    expect(JSON.parse(String(updateInit.body))).toEqual({ name: 'Stage', schemaName: 'STAGE_OWNER' })
    const [deletePath, deleteInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(deletePath).toBe('/api/v1/projects/project%20id/physical-schemas/physical%2Fschema')
    expect(deleteInit.method).toBe('DELETE')
  })

  it('captures a server-produced schema snapshot without sending client metadata', async () => {
    const fetchMock = mockResponse({ uuid: 'snapshot', serverProduced: true })

    await topologyApi.captureOracleSchemaSnapshot(
      'project id',
      'connection/id',
      'physical schema',
      'data/object',
    )

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/connections/connection%2Fid/physical-schemas/physical%20schema/data-objects/data%2Fobject/schema-snapshots:discover')
    expect(init.method).toBe('POST')
    expect(init.body).toBeUndefined()
  })

  it('defaults module labels to English and switches to Turkish explicitly', () => {
    expect(getTopologyCopy('de').connections).toBe('Connections')
    expect(getTopologyCopy('tr-TR').connections).toBe('Bağlantılar')
  })
})
