import type { Connection, ConnectionMode, ConnectionRequest } from './api'

export type IdentifierType = 'SERVICE_NAME' | 'SID'

export const DATABASE_TYPES = ['ORACLE', 'POSTGRESQL', 'MYSQL', 'SQLSERVER'] as const
export type DatabaseType = typeof DATABASE_TYPES[number]

export const DEFAULT_PORTS: Record<DatabaseType, string> = { ORACLE: '1521', POSTGRESQL: '5432', MYSQL: '3306', SQLSERVER: '1433' }

/** Flat editing state for a connection; every input is kept as text until it is converted to a request. */
export interface ConnectionDraft {
  databaseType: DatabaseType | ''
  code: string
  name: string
  description: string
  mode: ConnectionMode
  host: string
  port: string
  identifierType: IdentifierType
  /** Oracle service name / SID or the database name for other providers. */
  identifier: string
  jdbcUrlExtra: string
  jndiName: string
  username: string
  password: string
  fetchSize: string
  batchSize: string
  connectTimeoutMs: string
  readTimeoutMs: string
  queryTimeoutSeconds: string
  onConnectSql: string
  onDisconnectSql: string
}

export const initialConnectionDraft: ConnectionDraft = {
  databaseType: '', code: '', name: '', description: '', mode: 'JDBC', host: '', port: '1521',
  identifierType: 'SERVICE_NAME', identifier: '', jdbcUrlExtra: '', jndiName: '', username: '', password: '',
  fetchSize: '30', batchSize: '30', connectTimeoutMs: '10000', readTimeoutMs: '60000', queryTimeoutSeconds: '60',
  onConnectSql: '', onDisconnectSql: '',
}

export function draftFromConnection(connection: Connection): ConnectionDraft {
  return {
    databaseType: (DATABASE_TYPES as readonly string[]).includes(connection.databaseType) ? connection.databaseType as DatabaseType : '',
    code: connection.code, name: connection.name, description: connection.description ?? '',
    mode: connection.mode, host: connection.host ?? '', port: String(connection.port ?? DEFAULT_PORTS[connection.databaseType as DatabaseType] ?? ''),
    identifierType: connection.sid ? 'SID' : 'SERVICE_NAME',
    identifier: connection.sid ?? connection.serviceName ?? connection.databaseName ?? '',
    jdbcUrlExtra: connection.jdbcUrlExtra ?? '', jndiName: connection.jndiName ?? '',
    username: connection.username ?? '', password: '',
    fetchSize: String(connection.fetchSize), batchSize: String(connection.batchSize),
    connectTimeoutMs: String(connection.connectTimeoutMs), readTimeoutMs: String(connection.readTimeoutMs),
    queryTimeoutSeconds: String(connection.queryTimeoutSeconds),
    onConnectSql: connection.onConnectSql ?? '', onDisconnectSql: connection.onDisconnectSql ?? '',
  }
}

function validInteger(value: string, minimum: number, maximum: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum
}

/** Returns the invalid field names; `hasStoredPassword` lets an edit keep the saved password. */
export function validateConnectionDraft(draft: ConnectionDraft, hasStoredPassword = false): string[] {
  const errors: string[] = []
  if (!draft.databaseType) errors.push('databaseType')
  if (!draft.name.trim()) errors.push('name')
  if (!/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(draft.code)) errors.push('code')
  if (!validInteger(draft.fetchSize, 1, 10_000)) errors.push('fetchSize')
  if (!validInteger(draft.batchSize, 1, 10_000)) errors.push('batchSize')
  if (!validInteger(draft.connectTimeoutMs, 1_000, 120_000)) errors.push('connectTimeoutMs')
  if (!validInteger(draft.readTimeoutMs, 1_000, 300_000)) errors.push('readTimeoutMs')
  if (!validInteger(draft.queryTimeoutSeconds, 1, 3_600)) errors.push('queryTimeoutSeconds')
  if (draft.mode === 'JNDI') {
    if (!/^java:comp\/env\/jdbc\/[A-Za-z0-9_.-]{1,180}$/.test(draft.jndiName)) errors.push('jndiName')
    return errors
  }
  if (!/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(draft.host) || draft.host.includes('..')) errors.push('host')
  if (!validInteger(draft.port, 1, 65_535)) errors.push('port')
  if (!/^[A-Za-z0-9_$#.-]{1,128}$/.test(draft.identifier)) errors.push('identifier')
  if (!draft.username.trim()) errors.push('username')
  if (!draft.password && !hasStoredPassword) errors.push('password')
  return errors
}

export function toConnectionRequest(draft: ConnectionDraft): ConnectionRequest {
  const common = {
    code: draft.code.trim().toUpperCase(), name: draft.name.trim(), description: draft.description.trim() || null,
    databaseType: draft.databaseType, mode: draft.mode,
    fetchSize: Number(draft.fetchSize), batchSize: Number(draft.batchSize),
    connectTimeoutMs: Number(draft.connectTimeoutMs), readTimeoutMs: Number(draft.readTimeoutMs),
    queryTimeoutSeconds: Number(draft.queryTimeoutSeconds),
    onConnectSql: draft.onConnectSql.trim() || null, onDisconnectSql: draft.onDisconnectSql.trim() || null,
  }
  if (draft.mode === 'JNDI') return { ...common, jndiName: draft.jndiName.trim() }
  const identifier = draft.identifier.trim()
  const oracle = draft.databaseType === 'ORACLE'
  return {
    ...common,
    host: draft.host.trim(), port: Number(draft.port),
    serviceName: oracle && draft.identifierType === 'SERVICE_NAME' ? identifier : null,
    sid: oracle && draft.identifierType === 'SID' ? identifier : null,
    databaseName: oracle ? null : identifier,
    jdbcUrlExtra: draft.jdbcUrlExtra.trim() || null,
    username: draft.username.trim(),
    password: draft.password || null,
  }
}
