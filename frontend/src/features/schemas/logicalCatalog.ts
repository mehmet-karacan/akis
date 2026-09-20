import type { Connection, Environment, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'

export interface LogicalSchemaMapping {
  environment: Environment
  binding?: SchemaBinding
  physicalSchema?: PhysicalSchema
  connection?: Connection
}

export interface LogicalSchemaCatalogItem {
  schema: LogicalSchema
  mappings: LogicalSchemaMapping[]
  mappedCount: number
}

export type MappingState = 'complete' | 'partial' | 'none'

export function buildLogicalSchemaCatalog(
  schemas: LogicalSchema[],
  environments: Environment[],
  physicalSchemas: PhysicalSchema[],
  connections: Connection[],
  bindings: SchemaBinding[],
): LogicalSchemaCatalogItem[] {
  return schemas.map((schema) => {
    const mappings = environments.map((environment): LogicalSchemaMapping => {
      const binding = bindings.find((item) => item.logicalSchemaUuid === schema.uuid && item.environmentUuid === environment.uuid)
      const physicalSchema = binding ? physicalSchemas.find((item) => item.uuid === binding.physicalSchemaUuid) : undefined
      const connection = physicalSchema ? connections.find((item) => item.uuid === physicalSchema.connectionUuid) : undefined
      return { environment, binding, physicalSchema, connection }
    })
    return { schema, mappings, mappedCount: mappings.filter((item) => item.binding).length }
  })
}

export function mappingState(item: LogicalSchemaCatalogItem): MappingState {
  if (item.mappedCount === 0) return 'none'
  return item.mappedCount === item.mappings.length ? 'complete' : 'partial'
}

/** "TEST → SKY / TTBP" style label for one environment mapping. */
export function mappingLabel(mapping: LogicalSchemaMapping, notMapped: string) {
  if (!mapping.binding) return `${mapping.environment.code} → ${notMapped}`
  return `${mapping.environment.code} → ${mapping.connection?.code ?? '?'} / ${mapping.physicalSchema?.schemaName ?? '?'}`
}

/** Physical schemas a logical schema may bind to: same technology only. */
export function compatiblePhysicalSchemas(databaseType: string | null | undefined, physicalSchemas: PhysicalSchema[]) {
  return databaseType ? physicalSchemas.filter((item) => item.databaseType === databaseType) : physicalSchemas
}
