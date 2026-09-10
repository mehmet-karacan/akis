export const DEFINITION_TYPES = [
  'MAPPING',
  'REUSABLE_MAPPING',
  'PACKAGE',
  'PROCEDURE',
  'VARIABLE',
  'SEQUENCE',
  'USER_FUNCTION',
  'KNOWLEDGE_MODULE',
  'LOAD_PLAN',
] as const

export type DefinitionType = (typeof DEFINITION_TYPES)[number]

export interface DefinitionTypeDescriptor {
  code: DefinitionType
  label: string
  category: string
  folderRequired: boolean
  globalAllowed: boolean
  requiredContentFields: string[]
}

export interface Folder {
  uuid: string
  parentUuid: string | null
  code: string
  type: string | null
  status: string
  name: string
  description: string | null
  version: number
}

export interface Definition {
  uuid: string
  folderUuid: string | null
  type: DefinitionType
  code: string
  status: string
  name: string
  description: string | null
  version: number
}

export interface Draft {
  uuid: string
  schemaVersion: number
  content: unknown
  version: number
}

export interface DefinitionVersion {
  uuid: string
  versionNumber: number
  schemaVersion: number
  contentHash: string
  content: unknown
  description: string | null
  createdAt: string
}

export interface Scenario {
  uuid: string
  definitionUuid: string
  definitionVersionUuid: string
  definitionType: DefinitionType
  scenarioVersion: number
  planVersion: number
  planHash: string
  plan: unknown
  parameterSchema: unknown
  createdAt: string
}

export type BindingRole = 'SOURCE' | 'TARGET'

export interface DataBinding {
  uuid: string
  definitionUuid: string
  definitionVersionUuid: string
  nodeCode: string
  role: BindingRole
  dataObjectUuid: string
  schemaSnapshotUuid: string
  createdAt: string
}

export interface MappingDataset {
  id: string
  role: BindingRole
  name?: string
  [key: string]: unknown
}

export interface ColumnReference {
  dataset: string
  column: string
}

export interface ColumnMapping {
  source?: ColumnReference
  expression?: Record<string, unknown>
  target: ColumnReference
}

export interface MappingContent {
  datasets: MappingDataset[]
  columnMappings: ColumnMapping[]
  writeStrategy: {
    kind: 'APPEND' | 'STAGED_REPLACE' | 'MERGE' | 'TRUNCATE_LOAD'
    key?: string[]
  }
  [key: string]: unknown
}

export interface NewDefinitionInput {
  folderUuid: string | null
  type: DefinitionType
  code: string
  name: string
  description: string
}

