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

export type BindingRole = 'KAYNAK' | 'HEDEF'
export type DatasetRole = 'SOURCE' | 'TARGET'

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

export interface BindingCandidate {
  dataObjectUuid: string
  dataObjectCode: string
  dataObjectName: string
  objectReference: string
  schemaSnapshotUuid: string
  snapshotFingerprint: string
  discoveredAt: string
  physicalSchemaUuid: string
  physicalSchemaCode: string
  physicalSchemaReference: string
  connectionUuid: string
  connectionCode: string
  connectionName: string
  connectionVersionUuid: string
  connectionVersionNumber: number
  environmentCodes: string[]
}

export interface MappingDataset {
  id: string
  role: DatasetRole
  name?: string
  dataObjectUuid?: string
  schemaSnapshotUuid?: string
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
    kind: 'APPEND' | 'STAGED_REPLACE' | 'MERGE' | 'TRUNCATE_LOAD' | 'ATOMIC_DELETE_INSERT'
    key?: string[]
  }
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
  timeoutSeconds?: number
  output?: { kind: 'ROWSET'; maxRows: number }
  input?: { fromTask: string; mode: 'BATCH'; batchSize: number }
}

export interface ProcedureContent {
  tasks: ProcedureTask[]
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
