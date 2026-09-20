import type { Connection, Environment, LogicalSchema, PhysicalSchema, SchemaBinding } from './api'

/** Minimal valid records for unit tests; pass overrides for the fields a test cares about. */
export const connectionFixture = (overrides: Partial<Connection> & Pick<Connection, 'uuid'>): Connection => ({
  code: overrides.uuid.toUpperCase(), name: overrides.uuid, databaseType: 'ORACLE', mode: 'JDBC',
  host: 'db.example', port: 1521, serviceName: 'ORCL', username: 'app', hasPassword: true,
  fetchSize: 30, batchSize: 30, connectTimeoutMs: 10000, readTimeoutMs: 60000, queryTimeoutSeconds: 60,
  status: 'ETKIN', ...overrides,
})

export const physicalSchemaFixture = (overrides: Partial<PhysicalSchema> & Pick<PhysicalSchema, 'uuid' | 'connectionUuid' | 'schemaName'>): PhysicalSchema => ({
  code: overrides.schemaName, name: overrides.schemaName, databaseType: 'ORACLE', workSchemaName: overrides.schemaName,
  defaultSchema: false, loadingPrefix: 'C$_', integrationPrefix: 'I$_', errorPrefix: 'E$_', tempPrefix: 'T$_', status: 'ETKIN',
  ...overrides,
})

export const logicalSchemaFixture = (overrides: Partial<LogicalSchema> & Pick<LogicalSchema, 'uuid'>): LogicalSchema => ({
  code: overrides.uuid.toUpperCase(), name: overrides.uuid, databaseType: 'ORACLE', status: 'ETKIN', ...overrides,
})

export const environmentFixture = (overrides: Partial<Environment> & Pick<Environment, 'uuid'>): Environment => ({
  code: overrides.uuid.toUpperCase(), name: overrides.uuid, defaultEnvironment: false, policyVersion: 1, status: 'ETKIN', ...overrides,
})

export const bindingFixture = (overrides: Partial<SchemaBinding> & Pick<SchemaBinding, 'uuid' | 'logicalSchemaUuid' | 'environmentUuid' | 'physicalSchemaUuid'>): SchemaBinding => ({
  databaseType: 'ORACLE', ...overrides,
})
