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
  executionPolicy: Record<string, never>
} | {
  mode: 'JNDI'
  jndi: { name: string }
  policyVersion: 2
  executionPolicy: Record<string, never>
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

export interface ConnectionTestResult {
  connectionVersionUuid: string
  connected: boolean
  oracle19cCompatible: boolean
  databaseProduct: string
  databaseVersion: string
  databaseMajorVersion: number
  databaseMinorVersion: number
  driverName: string
  driverVersion: string
}

export interface DiscoveryColumn {
  name: string
  jdbcType: number
  producerType: string
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

type JsonRecord = Record<string, unknown>

const base = (projectUuid: string) => `/api/v1/projects/${encodeURIComponent(projectUuid)}`
const connectionVersionsV2 = (projectUuid: string, connectionUuid: string) =>
  `/api/v2/projects/${encodeURIComponent(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}/versions`

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
  testOracle: (projectUuid: string, connectionUuid: string, versionUuid: string) => post<ConnectionTestResult>(`${base(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}/versions/${encodeURIComponent(versionUuid)}/test`),
  discoverOracle: (projectUuid: string, connectionUuid: string, versionUuid: string, physicalSchemaUuid: string, body: JsonRecord) => post<DiscoveryResult>(`${base(projectUuid)}/connections/${encodeURIComponent(connectionUuid)}/versions/${encodeURIComponent(versionUuid)}/physical-schemas/${encodeURIComponent(physicalSchemaUuid)}/discover`, body),
}
