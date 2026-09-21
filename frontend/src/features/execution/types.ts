import type { Publication } from '../operations/types'

export type RunStatus =
  | 'BEKLIYOR'
  | 'CALISIYOR'
  | 'YENIDEN_DENENEBILIR'
  | 'MUDAHALE_GEREKLI'
  | 'BASARILI'
  | 'BASARISIZ'
  | 'IPTAL'
  | string

export interface RunRecord {
  jobRequestUuid: string
  runUuid: string
  publicationUuid: string
  attemptNumber: number
  startType: string
  status: RunStatus
  releaseHash: string
  planHash: string
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  cancellationRequestedAt: string | null
  allowedActions: Array<{ action: 'CANCEL' | 'START_NEW_ATTEMPT' | 'RESUME' | string; allowed: boolean; reasonCode: string | null }>
}

export type RunView = 'RECENT' | 'ACTIVE' | 'FAILED' | 'HISTORY'

export interface RunSearchInput {
  view: RunView
  query?: string
  statuses?: string
  environment?: string
  definitionType?: string
  from?: string
  to?: string
  page: number
  size: number
}

export interface RunSummary {
  run: RunRecord
  definitionUuid: string
  definitionCode: string
  definitionName: string
  definitionType: string
  environmentUuid: string
  environmentCode: string
  environmentName: string
  environmentRisk: string
  initiatorName: string
  selectedRows: number | null
  insertedRows: number | null
  selectedRowsExact?: string | null
  insertedRowsExact?: string | null
}

export interface RunPage {
  items: RunSummary[]
  total: number
  page: number
  size: number
}

export type Run = RunRecord

export interface RunEvent {
  uuid: string
  eventNumber: number
  type: string
  eventTime: string
  data: unknown
}

export interface RunEventPage { items: RunEvent[]; nextCursor: number | null; hasMore: boolean }

export interface RunStep {
  logCounter?: string | null
  transactionState?: 'COMMITTED' | 'UNCONFIRMED' | 'NOT_APPLICABLE'
  uuid: string
  parentUuid: string | null
  code: string
  type: string
  ordinal: number
  name: string
  status: string
  connectionRole: string | null
  risk: string | null
  startedAt: string | null
  finishedAt: string | null
  rowCount: number | null
  byteCount: number | null
  rowCountExact?: string | null
  byteCountExact?: string | null
  errorCode: string | null
  /** Package step: the child run that executed the step's object. */
  childRunUuid?: string | null
}

export interface RecoveryUnit {
  workUnitKey: string
  stepUuid: string | null
  stepCode: string
  kind: string
  decision: 'RUN' | 'SKIP_WITH_EVIDENCE' | 'REPLAY_DEPENDENCY' | 'RECONCILE_REQUIRED' | 'BLOCKED'
  transactionOutcome: 'NOT_ATTEMPTED' | 'EXECUTED_UNCOMMITTED' | 'COMMIT_CONFIRMED' | 'ROLLBACK_CONFIRMED' | 'OUTCOME_UNKNOWN'
  evidenceReference: string | null
  reasonCode: string | null
}

export interface RecoveryPlan {
  evidenceVersion: number
  runUuid: string
  expectedStateVersion: string
  planHash: string
  allowedActions: string[]
  reasonCodes: string[]
  units: RecoveryUnit[]
  reconciliationRequired: boolean
  preservesWorkspace: boolean
  resetsTarget: boolean
}

export interface TransferChunk {
  uuid: string
  sequence: string
  partitionCode: string
  lowerExclusive: string | null
  upperInclusive: string | null
  lastKey: string | null
  payloadHash: string
  rowCountExact: string
  byteCountExact: string
  status: string
  targetReceiptReference: string | null
  createdAt: string
}

export interface TransferChunkPage {
  items: TransferChunk[]
  nextCursor: string | null
  hasMore: boolean
}

export function exactCount(value: string | null | undefined) {
  return value == null ? null : BigInt(value)
}

export interface ProjectCapabilities {
  contractVersion: 1
  procedure: { maximumTasks: number; maximumRowsetRows: number; maximumTimeoutSeconds: number }
  runtime: { acceptsManualRequests: boolean; workerAvailable: boolean; runnable: boolean; unavailableReason: string | null }
  supportedRuntimeCapabilities: string[]
}

const executableRuntimeCapabilities = new Set([
  'ORACLE_TABLE_COPY_V1',
  'ORACLE_PROCEDURE_V1',
])

export function publicationRuntimeCapability(publication: Publication) {
  if (!publication.physicalManifest || typeof publication.physicalManifest !== 'object' || Array.isArray(publication.physicalManifest)) return null
  const capability = (publication.physicalManifest as Record<string, unknown>).runtimeCapability
  return typeof capability === 'string' ? capability : null
}

export function isRunnablePublication(publication: Publication) {
  const capability = publicationRuntimeCapability(publication)
  return publication.status === 'AKTIF' && capability !== null && executableRuntimeCapabilities.has(capability)
}
