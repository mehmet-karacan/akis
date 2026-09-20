import type { Connection, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'

export interface ConnectionCatalogItem {
  connection: Connection
  physicalSchemaCount: number
  logicalSchemaCount: number
}

export function buildConnectionCatalog(
  connections: Connection[],
  physicalSchemas: PhysicalSchema[],
  logicalSchemas: LogicalSchema[],
  bindings: SchemaBinding[],
): ConnectionCatalogItem[] {
  return connections.map((connection) => {
    const physicalIds = new Set(physicalSchemas.filter((schema) => schema.connectionUuid === connection.uuid).map((schema) => schema.uuid))
    const logicalIds = new Set(bindings.filter((binding) => physicalIds.has(binding.physicalSchemaUuid)).map((binding) => binding.logicalSchemaUuid))
    return {
      connection,
      physicalSchemaCount: physicalIds.size,
      logicalSchemaCount: logicalSchemas.filter((schema) => logicalIds.has(schema.uuid)).length,
    }
  })
}

export function endpointLabel(connection?: Connection) {
  if (!connection) return 'Tanımlanmadı'
  if (connection.mode === 'JNDI') return connection.jndiName ?? 'JNDI'
  const identifier = connection.serviceName ?? connection.sid ?? connection.databaseName
  return `${connection.host ?? 'Tanımlanmadı'}:${connection.port ?? 'Tanımlanmadı'}${identifier ? ` / ${identifier}` : ''}`
}
