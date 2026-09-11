import { apiRequest, jsonBody } from '../../core/api/client'

export interface SecretReference {
  uuid: string
  code: string
  referencePath: string
  versionReference?: string | null
  provider: string
  status: string
  name: string
  version: number
}

export interface Connection {
  uuid: string
  code: string
  databaseType: string
  status: string
  name: string
  description?: string | null
  version: number
}

export interface ConnectionVersion {
  uuid: string
  versionNumber: number
  mode: 'JDBC' | 'JNDI'
  driverReference?: string | null
  host?: string | null
  serviceName?: string | null
  sid?: string | null
  databaseName?: string | null
  jndiName?: string | null
  tlsMode?: string | null
  port?: number | null
  policyVersion: number
  policy?: unknown
  createdAt: string
  lifecycleStatus: 'DRAFT' | 'TESTED' | 'ACTIVE'
  lifecycleVersion: number
  targetIdentityVersion?: number | null
  targetFingerprint?: string | null
  latestSuccessfulTestUuid?: string | null
  testedAt?: string | null
  activatedAt?: string | null
  runtimeCapability: 'EXECUTABLE' | 'TEST_DISCOVERY_ONLY'
}

export interface ConnectionExecutionPolicy {
  connectTimeoutMs: number
  readTimeoutMs: number
  networkTimeoutMs: number
  queryTimeoutSeconds: number
}

export type CreateConnectionVersionRequest = {
  mode: 'JDBC'
  jdbc: {
    host: string
    port: number
    connectIdentifier: { type: 'SERVICE_NAME' | 'SID'; value: string }
    transport: 'TCP'
    credentialSecretReferenceUuid: string
  }
  policyVersion: 2
  executionPolicy: ConnectionExecutionPolicy
} | {
  mode: 'JNDI'
  jndi: { name: string }
  policyVersion: 2
  executionPolicy: ConnectionExecutionPolicy
}

export interface PhysicalSchema {
  uuid: string
  connectionUuid: string
  code: string
  schemaReference: string
  status: string
  name: string
  version: number
}

export interface LogicalSchema {
  uuid: string
  code: string
  status: string
  name: string
  description?: string | null
  version: number
}

export interface Environment {
  uuid: string
  code: string
  risk?: string | null
  status: string
  policyVersion: number
  policy?: unknown
  name: string
  version: number
}

export interface SchemaBinding {
  uuid: string
  logicalSchemaUuid: string
  environmentUuid: string
  physicalSchemaUuid: string
  connectionVersionUuid: string
  status: string
  version: number
}

export interface Model {
  uuid: string
  logicalSchemaUuid: string
  code: string
  status: string
  name: string
  description?: string | null
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

export interface ConnectionTestProbe {
  databaseProduct: string
  databaseVersion: string
  databaseMajorVersion: number
  databaseMinorVersion: number
  driverName: string
  driverVersion: string
}

export interface ConnectionTestAttempt {
  uuid: string
  connectionVersionUuid: string
  attemptNumber: number
  outcome: 'PASSED' | 'FAILED' | 'TARGET_MISMATCH'
  errorCode?: string | null
  probe?: ConnectionTestProbe | null
  targetIdentityVersion?: number | null
  targetFingerprint?: string | null
  startedAt: string
  completedAt: string
  durationMs: number
}

export interface ConnectionVersionLifecycle {
  connectionVersionUuid: string
  status: 'DRAFT' | 'TESTED' | 'ACTIVE'
  stateVersion: number
  targetIdentityVersion?: number | null
  targetFingerprint?: string | null
  latestSuccessfulTestUuid?: string | null
  testedAt?: string | null
  activatedAt?: string | null
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
  connectionVersionUuid: string
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
const connectionVersionsV2 = (projectUuid: string, connectionUuid: string) =>
  `/api/v2/projects/${encodeURIComponent(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}/versions`
const connectionVersionV2 = (projectUuid: string, connectionUuid: string, versionUuid: string) =>
  `${connectionVersionsV2(projectUuid, connectionUuid)}/${encodeURIComponent(versionUuid)}`

const get = <T>(path: string) => apiRequest<T>(path)
const post = <T>(path: string, body?: JsonRecord) => apiRequest<T>(path, {
  method: 'POST',
  ...(body ? jsonBody(body) : {}),
})

export const topologyApi = {
  listSecrets: (projectUuid: string) => get<SecretReference[]>(`${base(projectUuid)}/secret-references`),
  createSecret: (projectUuid: string, body: JsonRecord) => post<SecretReference>(`${base(projectUuid)}/secret-references`, body),
  listConnections: (projectUuid: string) => get<Connection[]>(`${base(projectUuid)}/connections`),
  createConnection: (projectUuid: string, body: JsonRecord) => post<Connection>(`${base(projectUuid)}/connections`, body),
  listVersions: (projectUuid: string, connectionUuid: string) => get<ConnectionVersion[]>(connectionVersionsV2(projectUuid, connectionUuid)),
  createVersion: (projectUuid: string, connectionUuid: string, body: CreateConnectionVersionRequest) => post<ConnectionVersion>(connectionVersionsV2(projectUuid, connectionUuid), body),
  testConnectionVersion: (projectUuid: string, connectionUuid: string, versionUuid: string) =>
    post<ConnectionTestAttempt>(`${connectionVersionV2(projectUuid, connectionUuid, versionUuid)}/tests`),
  listConnectionVersionTests: (projectUuid: string, connectionUuid: string, versionUuid: string, limit = 20) =>
    get<ConnectionTestAttempt[]>(`${connectionVersionV2(projectUuid, connectionUuid, versionUuid)}/tests?limit=${encodeURIComponent(String(limit))}`),
  activateConnectionVersion: (
    projectUuid: string,
    connectionUuid: string,
    versionUuid: string,
    body: { testUuid: string; expectedStateVersion: number },
  ) => post<ConnectionVersionLifecycle>(`${connectionVersionV2(projectUuid, connectionUuid, versionUuid)}/activate`, body),
  listPhysicalSchemas: (projectUuid: string) => get<PhysicalSchema[]>(`${base(projectUuid)}/physical-schemas`),
  createPhysicalSchema: (projectUuid: string, body: JsonRecord) => post<PhysicalSchema>(`${base(projectUuid)}/physical-schemas`, body),
  listLogicalSchemas: (projectUuid: string) => get<LogicalSchema[]>(`${base(projectUuid)}/logical-schemas`),
  createLogicalSchema: (projectUuid: string, body: JsonRecord) => post<LogicalSchema>(`${base(projectUuid)}/logical-schemas`, body),
  listEnvironments: (projectUuid: string) => get<Environment[]>(`${base(projectUuid)}/environments`),
  createEnvironment: (projectUuid: string, body: JsonRecord) => post<Environment>(`${base(projectUuid)}/environments`, body),
  listBindings: (projectUuid: string) => get<SchemaBinding[]>(`${base(projectUuid)}/schema-bindings`),
  createBinding: (projectUuid: string, body: JsonRecord) => post<SchemaBinding>(`${base(projectUuid)}/schema-bindings`, body),
  listModels: (projectUuid: string) => get<Model[]>(`${base(projectUuid)}/models`),
  createModel: (projectUuid: string, body: JsonRecord) => post<Model>(`${base(projectUuid)}/models`, body),
  listSubmodels: (projectUuid: string, modelUuid: string) => get<Submodel[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/submodels`),
  createSubmodel: (projectUuid: string, modelUuid: string, body: JsonRecord) => post<Submodel>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/submodels`, body),
  listDataObjects: (projectUuid: string, modelUuid: string) => get<DataObject[]>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects`),
  createDataObject: (projectUuid: string, modelUuid: string, body: JsonRecord) => post<DataObject>(`${base(projectUuid)}/models/${encodeURIComponent(modelUuid)}/data-objects`, body),
  discoverOracle: (projectUuid: string, connectionUuid: string, versionUuid: string, physicalSchemaUuid: string, body: JsonRecord) => post<DiscoveryResult>(`${base(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}/versions/${encodeURIComponent(versionUuid)}/physical-schemas/${encodeURIComponent(physicalSchemaUuid)}/discover`, body),
  captureOracleSchemaSnapshot: (
    projectUuid: string,
    connectionUuid: string,
    versionUuid: string,
    physicalSchemaUuid: string,
    dataObjectUuid: string,
  ) => post<SchemaSnapshot>(`${connectionVersionV2(projectUuid, connectionUuid, versionUuid)}/physical-schemas/${encodeURIComponent(physicalSchemaUuid)}/data-objects/${encodeURIComponent(dataObjectUuid)}/schema-snapshots:discover`),
}
