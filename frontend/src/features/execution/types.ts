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
}

export type Run = RunRecord

export interface RunEvent {
  uuid: string
  eventNumber: number
  type: string
  eventTime: string
  data: unknown
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
