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
