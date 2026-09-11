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

export type Run = RunRecord

export interface RunEvent {
  uuid: string
  eventNumber: number
  type: string
  eventTime: string
  data: unknown
}

export interface RunStep {
  uuid: string
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
  errorCode: string | null
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
