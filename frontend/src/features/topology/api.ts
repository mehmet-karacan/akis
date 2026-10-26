import { apiRequest, jsonBody } from '../../core/api/client'

export type ConnectionMode = 'JDBC' | 'JNDI'

export interface Connection {
  uuid: string
  code: string
  name: string
  description?: string | null
  databaseType: string
  mode: ConnectionMode
  driverReference?: string | null
  host?: string | null
  port?: number | null
  serviceName?: string | null
  sid?: string | null
  databaseName?: string | null
  jdbcUrlExtra?: string | null
  jndiName?: string | null
  username?: string | null
  hasPassword: boolean
  fetchSize: number
  batchSize: number
  connectTimeoutMs: number
  readTimeoutMs: number
  queryTimeoutSeconds: number
  onConnectSql?: string | null
  onDisconnectSql?: string | null
  lastTestedAt?: string | null
  lastTestPassed?: boolean | null
  status: string
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** Body for create, update (PATCH) and draft test of a connection. */
export interface ConnectionRequest {
  code: string
  name: string
  description?: string | null
  databaseType: string
  mode: ConnectionMode
  driverReference?: string | null
  host?: string | null
  port?: number | null
  serviceName?: string | null
  sid?: string | null
  databaseName?: string | null
  jdbcUrlExtra?: string | null
  jndiName?: string | null
  username?: string | null
  /** Omit on update to keep the stored password. */
  password?: string | null
  fetchSize?: number
  batchSize?: number
  connectTimeoutMs?: number
  readTimeoutMs?: number
  queryTimeoutSeconds?: number
  onConnectSql?: string | null
  onDisconnectSql?: string | null
  status?: string
}

export interface ConnectionCatalogProjection {
  connection: Connection
  physicalSchemaCount: number
  logicalSchemaCount: number
}

export interface ConnectionDependency {
  uuid: string
  type: 'LOGICAL_SCHEMA' | string
  name: string
}

export interface ConnectionTestProbe {
  connected: boolean
  databaseProduct: string
  databaseVersion: string
  databaseMajorVersion?: number | null
  databaseMinorVersion?: number | null
  driverName: string
  driverVersion: string
}

export interface ConnectionTestAttempt {
  uuid: string
  attemptNumber: number
  outcome: 'PASSED' | 'FAILED' | 'TARGET_MISMATCH'
  errorCode?: string | null
  probe?: ConnectionTestProbe | null
  startedAt: string
  completedAt: string
  durationMs: number
}

export interface PhysicalSchema {
  uuid: string
  connectionUuid: string
  code: string
  name: string
  description?: string | null
  databaseType: string
  catalogName?: string | null
  schemaName: string
  workCatalogName?: string | null
  workSchemaName?: string | null
  defaultSchema: boolean
  loadingPrefix: string
  integrationPrefix: string
  errorPrefix: string
  tempPrefix: string
  objectPattern?: string | null
  remoteObjectPattern?: string | null
  sequencePattern?: string | null
  status: string
}

export interface PhysicalSchemaRequest {
  connectionUuid?: string
  code?: string
  name?: string
  description?: string | null
  catalogName?: string | null
  schemaName: string
  workCatalogName?: string | null
  workSchemaName?: string | null
  defaultSchema?: boolean
  loadingPrefix?: string
  integrationPrefix?: string
  errorPrefix?: string
  tempPrefix?: string
  objectPattern?: string | null
  remoteObjectPattern?: string | null
  sequencePattern?: string | null
  status?: string
}

export interface LogicalSchema {
  uuid: string
  code: string
  name: string
  description?: string | null
  databaseType?: string | null
  status: string
}

export interface Environment {
  uuid: string
  code: string
  name: string
  description?: string | null
  risk?: string | null
  defaultEnvironment: boolean
  policyVersion: number
  policy?: unknown
  status: string
}

export type EnvironmentRisk = 'DUSUK' | 'ORTA' | 'YUKSEK' | 'URETIM'

export interface CreateEnvironmentRequest extends JsonRecord {
  code: string
  name: string
  description?: string | null
  risk?: EnvironmentRisk
  defaultEnvironment?: boolean
}

export interface UpdateEnvironmentRequest extends JsonRecord {
  name: string
  description?: string | null
  risk?: EnvironmentRisk
  defaultEnvironment?: boolean
  status?: string
}

export interface SchemaBinding {
  uuid: string
  logicalSchemaUuid: string
  environmentUuid: string
  physicalSchemaUuid: string
  databaseType: string
}

export interface Model {
  uuid: string
  logicalSchemaUuid: string
  technologyCode?: 'ORACLE' | string
  reverseEnvironmentUuid?: string | null
  reverseMode?: 'STANDARD' | 'CUSTOM_RKM'
  rkmDefinitionUuid?: string | null
  reverseOptions?: Record<string, unknown>
  code: string
  status: string
  name: string
  description?: string | null
  dataObjectCount?: number
  lastMetadataUpdate?: string | null
  version: number
}

export interface Submodel {
  uuid: string
  modelUuid: string
  parentUuid?: string | null
  code: string
  name: string
  version: number
}

export interface DataObject {
  uuid: string
  modelUuid: string
  submodelUuid?: string | null
  code: string
  objectReference: string
  type: string
  status: string
  querySchemaVersion?: number | null
  queryDefinition?: unknown
  name: string
  version: number
}

export interface DiscoveryColumn {
  name: string
  jdbcType: number
  producerType: string
  canonicalType: 'INTEGER' | 'DECIMAL' | 'STRING' | 'TIMESTAMP' | 'TEXT' | 'BINARY' | 'OFFSET_TIMESTAMP' | 'UNKNOWN'
  executionCapability: 'TRANSFER_SUPPORTED' | 'CATALOG_ONLY' | 'UNSUPPORTED'
  ordinal: number
  precision?: number | null
  scale?: number | null
  nullable: boolean
  defaultExpression?: string | null
}

export interface DiscoveryTable {
  owner: string
  name: string
  type: string
  columns: DiscoveryColumn[]
  constraints: Array<{
    name: string
    type: string
    columns: string[]
    referencedOwner?: string | null
    referencedTable?: string | null
  }>
}

export interface DiscoveryResult {
  connectionUuid: string
  physicalSchemaUuid: string
  owner: string
  discoveredAt: string
  truncated: boolean
  tables: DiscoveryTable[]
}

export interface SchemaSnapshotColumn {
  reference: string
  producerType: string
  canonicalType: string
  ordinal: number
  precision?: number | null
  scale?: number | null
  length?: number | null
  timePrecision?: number | null
  nullable: boolean
}

export interface SchemaSnapshotConstraint {
  externalReference: string
  type: string
  enabled: boolean
  name: string
  columnReferences: string[]
}

export interface SchemaSnapshot {
  uuid: string
  dataObjectUuid: string
  physicalSchemaUuid: string
  connectionVersionUuid: string
  fingerprint: string
  engineVersion: string
  discoveredAt: string
  createdAt: string
  columns: SchemaSnapshotColumn[]
  constraints: SchemaSnapshotConstraint[]
  serverProduced: true
  reused?: boolean
}

type JsonRecord = Record<string, unknown>

const base = (projectUuid: string) => `/api/v1/projects/${encodeURIComponent(projectUuid)}`
const connectionPath = (projectUuid: string, connectionUuid: string) =>
  `${base(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}`

const get = <T>(path: string) => apiRequest<T>(path)
const post = <T>(path: string, body?: JsonRecord) => apiRequest<T>(path, {
  method: 'POST',
  ...(body ? jsonBody(body) : {}),
})
const put = <T>(path: string, body: JsonRecord) => apiRequest<T>(path, { method: 'PUT', ...jsonBody(body) })
const patch = <T>(path: string, body: JsonRecord) => apiRequest<T>(path, {
  method: 'PATCH',
  ...jsonBody(body),
})
const remove = (path: string) => apiRequest<void>(path, { method: 'DELETE' })
const body = (value: object) => value as JsonRecord

export const topologyApi = {
  listConnections: (projectUuid: string) => get<Connection[]>(`${base(projectUuid)}/connections`),
  getConnection: (projectUuid: string, connectionUuid: string) => get<Connection>(connectionPath(projectUuid, connectionUuid)),
  listConnectionCatalog: (projectUuid: string) => get<ConnectionCatalogProjection[]>(`${base(projectUuid)}/connections/catalog`),
  listConnectionDependencies: (projectUuid: string, connectionUuid: string) => get<ConnectionDependency[]>(`${connectionPath(projectUuid, connectionUuid)}/dependencies`),
  createConnection: (projectUuid: string, request: ConnectionRequest) => post<Connection>(`${base(projectUuid)}/connections`, body(request)),
  updateConnection: (projectUuid: string, connectionUuid: string, request: ConnectionRequest) =>
    patch<Connection>(connectionPath(projectUuid, connectionUuid), body(request)),
  deleteConnection: (projectUuid: string, connectionUuid: string) => remove(connectionPath(projectUuid, connectionUuid)),
  testDraftConnection: (projectUuid: string, request: ConnectionRequest) =>
    post<ConnectionTestProbe>(`${base(projectUuid)}/connections/test`, body(request)),
  testConnection: (projectUuid: string, connectionUuid: string) =>
    post<ConnectionTestAttempt>(`${connectionPath(projectUuid, connectionUuid)}/tests`),
  listConnectionTests: (projectUuid: string, connectionUuid: string, limit = 20) =>
    get<ConnectionTestAttempt[]>(`${connectionPath(projectUuid, connectionUuid)}/tests?limit=${encodeURIComponent(String(limit))}`),
  listDatabaseSchemas: (projectUuid: string, connectionUuid: string) =>
    get<string[]>(`${connectionPath(projectUuid, connectionUuid)}/schemas`),
  listPhysicalSchemas: (projectUuid: string) => get<PhysicalSchema[]>(`${base(projectUuid)}/physical-schemas`),
  createPhysicalSchema: (projectUuid: string, request: PhysicalSchemaRequest) => post<PhysicalSchema>(`${base(projectUuid)}/physical-schemas`, body(request)),
  updatePhysicalSchema: (projectUuid: string, uuid: string, request: PhysicalSchemaRequest) => patch<PhysicalSchema>(`${base(projectUuid)}/physical-schemas/${encodeURIComponent(uuid)}`, body(request)),
  deletePhysicalSchema: (projectUuid: string, uuid: string) => remove(`${base(projectUuid)}/physical-schemas/${encodeURIComponent(uuid)}`),
  listLogicalSchemas: (projectUuid: string) => get<LogicalSchema[]>(`${base(projectUuid)}/logical-schemas`),
  updateContext: (projectUuid: string, kind: 'logical-schemas' | 'environments', uuid: string, request: JsonRecord) => patch<LogicalSchema | Environment>(`${base(projectUuid)}/${kind}/${encodeURIComponent(uuid)}`, request),
  deleteContext: (projectUuid: string, kind: 'logical-schemas' | 'environments', uuid: string) => remove(`${base(projectUuid)}/${kind}/${encodeURIComponent(uuid)}`),
  createLogicalSchema: (projectUuid: string, request: JsonRecord) => post<LogicalSchema>(`${base(projectUuid)}/logical-schemas`, request),
  listEnvironments: (projectUuid: string) => get<Environment[]>(`${base(projectUuid)}/environments`),
  createEnvironment: (projectUuid: string, request: CreateEnvironmentRequest) => post<Environment>(`${base(projectUuid)}/environments`, request),
  updateEnvironment: (projectUuid: string, uuid: string, request: UpdateEnvironmentRequest) => patch<Environment>(`${base(projectUuid)}/environments/${encodeURIComponent(uuid)}`, request),
  deleteEnvironment: (projectUuid: string, uuid: string) => remove(`${base(projectUuid)}/environments/${encodeURIComponent(uuid)}`),
  listBindings: (projectUuid: string) => get<SchemaBinding[]>(`${base(projectUuid)}/schema-bindings`),
  createBinding: (projectUuid: string, request: JsonRecord) => post<SchemaBinding>(`${base(projectUuid)}/schema-bindings`, request),
  updateBinding: (projectUuid: string, bindingUuid: string, request: JsonRecord) => patch<SchemaBinding>(`${base(projectUuid)}/schema-bindings/${encodeURIComponent(bindingUuid)}`, request),
  deleteBinding: (projectUuid: string, bindingUuid: string) => remove(`${base(projectUuid)}/schema-bindings/${encodeURIComponent(bindingUuid)}`),
  listModels: (projectUuid: string) => get<Model[]>(`${base(projectUuid)}/models`),
  getModel: (projectUuid: string, modelUuid: string) => get<Model>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}`),
  createModel: (projectUuid: string, request: JsonRecord) => post<Model>(`${base(projectUuid)}/models`, request),
  updateModel: (projectUuid: string, modelUuid: string, request: JsonRecord) => patch<Model>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}`, request),
  deleteModel: (projectUuid: string, modelUuid: string, expectedVersion: number) => remove(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}?expectedVersion=${expectedVersion}`),
  listSensitiveColumns: (projectUuid: string, modelUuid: string, objectUuid: string) => get<string[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects/${encodeURIComponent(objectUuid)}/sensitive-columns`),
  replaceSensitiveColumns: (projectUuid: string, modelUuid: string, objectUuid: string, columns: string[]) => put<string[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects/${encodeURIComponent(objectUuid)}/sensitive-columns`, { columns }),
  deleteDataObject: (projectUuid: string, modelUuid: string, objectUuid: string, expectedVersion: number) => remove(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects/${encodeURIComponent(objectUuid)}?expectedVersion=${expectedVersion}`),
  deleteSubmodel: (projectUuid: string, modelUuid: string, submodelUuid: string, expectedVersion: number) => remove(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/submodels/${encodeURIComponent(submodelUuid)}?expectedVersion=${expectedVersion}`),
  listSubmodels: (projectUuid: string, modelUuid: string) => get<Submodel[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/submodels`),
  createSubmodel: (projectUuid: string, modelUuid: string, request: JsonRecord) => post<Submodel>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/submodels`, request),
  listDataObjects: (projectUuid: string, modelUuid: string) => get<DataObject[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects`),
  createDataObject: (projectUuid: string, modelUuid: string, request: JsonRecord) => post<DataObject>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects`, request),
  moveDataObject: (projectUuid: string, modelUuid: string, objectUuid: string, request: { submodelUuid: string | null; expectedVersion: number }) => patch<DataObject>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects/${encodeURIComponent(objectUuid)}/folder`, request),
  discoverOracle: (projectUuid: string, connectionUuid: string, physicalSchemaUuid: string, request: JsonRecord) =>
    post<DiscoveryResult>(`${connectionPath(projectUuid, connectionUuid)}/physical-schemas/${encodeURIComponent(physicalSchemaUuid)}/discover`, request),
  captureOracleSchemaSnapshot: (projectUuid: string, connectionUuid: string, physicalSchemaUuid: string, dataObjectUuid: string) =>
    post<SchemaSnapshot>(`${connectionPath(projectUuid, connectionUuid)}/physical-schemas/${encodeURIComponent(physicalSchemaUuid)}/data-objects/${encodeURIComponent(dataObjectUuid)}/schema-snapshots:discover`),
  listSchemaSnapshots: (projectUuid: string, dataObjectUuid: string) =>
    get<SchemaSnapshot[]>(`${base(projectUuid)}/data-objects/${encodeURIComponent(dataObjectUuid)}/schema-snapshots`),
}
