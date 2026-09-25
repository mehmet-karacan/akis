export const DEFINITION_TYPES = [
  'MAPPING',
  'REUSABLE_MAPPING',
  'PACKAGE',
  'PROCEDURE',
  'VARIABLE',
  'SEQUENCE',
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

export interface KnowledgeModuleVersion extends DefinitionVersion {
  definitionUuid: string
  definitionName: string
}

export interface DefinitionVersionSummary {
  definitionUuid: string
  latestVersionNumber: number
  versionCount: number
  latestCreatedAt: string | null
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

export type DatasetRole = 'SOURCE' | 'TARGET'
export interface MappingObjectReference {
  id: string
  alias: string
  dataObjectUuid?: string
  schemaSnapshotUuid?: string
  [key: string]: unknown
}

export interface ColumnReference {
  object: string
  column: string
}

export interface ColumnMapping {
  source?: ColumnReference
  expression?: Record<string, unknown>
  target: ColumnReference
}

export type MappingJoinType = 'INNER' | 'LEFT' | 'RIGHT' | 'FULL'

export interface MappingJoin {
  id: string
  type: MappingJoinType
  left: ColumnReference
  right: ColumnReference
}

export interface MappingFilter {
  id: string
  scope: 'SOURCE' | 'GLOBAL'
  object: string
  column?: string
  operator?: 'EQUALS' | 'NOT_EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'LIKE' | 'IS_NULL' | 'IS_NOT_NULL'
  value?: string
  predicate?: Record<string, unknown>
}

export interface MappingContent {
  sources: MappingObjectReference[]
  target: MappingObjectReference
  joins: MappingJoin[]
  filters: MappingFilter[]
  columnMappings: ColumnMapping[]
  [key: string]: unknown
}

export type ProcedureTaskType = 'SQL' | 'PLSQL' | 'STORED_PROCEDURE'
export type ProcedureConnectionRole = 'SOURCE' | 'TARGET'
export type ProcedureRiskClass = 'READ_ONLY' | 'DML' | 'DDL' | 'DESTRUCTIVE'
export type { ProcedureLogCounter } from './procedureCatalog'

import type { ProcedureLogCounter } from './procedureCatalog'

export interface ProcedureTask {
  id: string
  name?: string
  type: ProcedureTaskType
  connectionRole: ProcedureConnectionRole
  riskClass: ProcedureRiskClass
  command: string
  /** Legacy values are accepted only to migrate drafts created before the ODI counter contract was corrected. */
  logCounter?: ProcedureLogCounter | 'ANALYSIS' | 'STATISTICS'
  transactionMode?: 'AUTOCOMMIT' | 'TRANSACTION'
  transactionChannel?: number
  transactionIsolation?: 'DRIVER_DEFAULT' | 'READ_COMMITTED' | 'SERIALIZABLE'
  commitMode?: 'NO_COMMIT' | 'COMMIT'
  logicalSchemaUuid?: string
  environmentUuid?: string
  requiresApproval?: boolean
  onError?: 'STOP' | 'CONTINUE'
  /** ODI "Execute" flag; a disabled step stays authored but is left out of the runtime plan. */
  enabled?: boolean
  timeoutSeconds?: number
  output?: { kind: 'ROWSET'; maxRows: number }
  input?: { fromTask: string; mode: 'BATCH'; batchSize: number }
  parameters?: Record<string, { type: 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP'; value?: string; valueSource?: 'VALUE' | 'REFRESH_QUERY'; query?: string; definitionUuid?: string; logicalSchemaUuid?: string; historyMode?: 'NONE' | 'LATEST' | 'ALL' }>
}

export interface ProcedureContent {
  tasks: ProcedureTask[]
  /** ODI Definition tab: source/target technology chosen for the whole procedure; steps pick logical schemas of that technology. */
  technology?: { source?: string; target?: string; multiConnection?: boolean }
  [key: string]: unknown
}

export interface NewDefinitionInput {
  folderUuid: string | null
  type: DefinitionType
  code: string
  name: string
  description: string
}

export interface NewFolderInput {
  parentUuid: string | null
  code: string
  name: string
  description: string
}

export interface MoveDefinitionInput {
  folderUuid: string | null
  expectedVersion: number
}

export interface MoveFolderInput {
  parentUuid: string | null
  expectedVersion: number
}
