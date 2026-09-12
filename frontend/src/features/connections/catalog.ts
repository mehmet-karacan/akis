import type { Connection, ConnectionVersion, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'

export interface ConnectionCatalogItem {
  connection: Connection
  displayedVersion?: ConnectionVersion
  latestVersionNumber?: number
  physicalSchemaCount: number
  logicalSchemaCount: number
}

export function buildConnectionCatalog(
  connections: Connection[],
  versions: Record<string, ConnectionVersion[]>,
  physicalSchemas: PhysicalSchema[],
  logicalSchemas: LogicalSchema[],
  bindings: SchemaBinding[],
): ConnectionCatalogItem[] {
  return connections.map((connection) => {
    const connectionVersions = versions[connection.uuid] ?? []
    const displayedVersion = connectionVersions.find((version) => version.lifecycleStatus === 'ACTIVE') ?? connectionVersions[0]
    const physicalIds = new Set(physicalSchemas.filter((schema) => schema.connectionUuid === connection.uuid).map((schema) => schema.uuid))
    const logicalIds = new Set(bindings.filter((binding) => physicalIds.has(binding.physicalSchemaUuid)).map((binding) => binding.logicalSchemaUuid))
    return {
      connection,
      displayedVersion,
      latestVersionNumber: connectionVersions[0]?.versionNumber,
      physicalSchemaCount: physicalIds.size,
      logicalSchemaCount: logicalSchemas.filter((schema) => logicalIds.has(schema.uuid)).length,
    }
  })
}

export function endpointLabel(version?: ConnectionVersion) {
  if (!version) return '—'
  if (version.mode === 'JNDI') return version.jndiName ?? 'JNDI'
  const identifier = version.serviceName ?? version.sid
  return `${version.host ?? '—'}:${version.port ?? '—'}${identifier ? ` / ${identifier}` : ''}`
}
