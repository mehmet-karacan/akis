import type { ConnectionVersion, PhysicalSchema, SchemaBinding } from '../topology/api'

export function compatibleVersions(
  physicalSchemaUuid: string,
  physicalSchemas: PhysicalSchema[],
  versions: Record<string, ConnectionVersion[]>,
) {
  const physical = physicalSchemas.find((item) => item.uuid === physicalSchemaUuid)
  if (!physical) return []
  return (versions[physical.connectionUuid] ?? []).filter((item) => item.mode === 'JDBC' && item.runtimeCapability === 'EXECUTABLE')
}

export function bindingForContext(bindings: SchemaBinding[], logicalSchemaUuid: string, environmentUuid: string) {
  return bindings.find((item) => item.logicalSchemaUuid === logicalSchemaUuid && item.environmentUuid === environmentUuid)
}
