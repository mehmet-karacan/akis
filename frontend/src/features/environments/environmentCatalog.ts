import type { Connection, Environment, EnvironmentRisk, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'

export interface EnvironmentMapping {
  logicalSchema: LogicalSchema
  binding?: SchemaBinding
  physicalSchema?: PhysicalSchema
  connection?: Connection
}

export interface EnvironmentCatalogItem {
  environment: Environment
  mappings: EnvironmentMapping[]
  mappedCount: number
}

export const ENVIRONMENT_RISKS: EnvironmentRisk[] = ['DUSUK', 'ORTA', 'YUKSEK', 'URETIM']

export function riskLabel(risk: string | null | undefined, tr: boolean) {
  switch (risk) {
    case 'ORTA': return tr ? 'Orta' : 'Medium'
    case 'YUKSEK': return tr ? 'Yüksek' : 'High'
    case 'URETIM': return tr ? 'Üretim' : 'Production'
    default: return tr ? 'Düşük' : 'Low'
  }
}

export function riskTone(risk: string | null | undefined): 'success' | 'warning' | 'danger' {
  if (risk === 'URETIM') return 'danger'
  if (risk === 'ORTA' || risk === 'YUKSEK') return 'warning'
  return 'success'
}

export function buildEnvironmentCatalog(
  environments: Environment[],
  logicalSchemas: LogicalSchema[],
  physicalSchemas: PhysicalSchema[],
  connections: Connection[],
  bindings: SchemaBinding[],
): EnvironmentCatalogItem[] {
  return environments.map((environment) => {
    const mappings = logicalSchemas.map((logicalSchema): EnvironmentMapping => {
      const binding = bindings.find((item) => item.environmentUuid === environment.uuid && item.logicalSchemaUuid === logicalSchema.uuid)
      const physicalSchema = binding ? physicalSchemas.find((item) => item.uuid === binding.physicalSchemaUuid) : undefined
      const connection = physicalSchema ? connections.find((item) => item.uuid === physicalSchema.connectionUuid) : undefined
      return { logicalSchema, binding, physicalSchema, connection }
    })
    return { environment, mappings, mappedCount: mappings.filter((item) => item.binding).length }
  })
}
