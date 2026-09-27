export type ExportScope = 'VISIBLE' | 'FILTERED' | 'ALL' | 'SELECTED'

export interface ExportFilter {
  field: string
  operator: string
  value: unknown
}

export interface ExportRequest {
  providerId: string
  resourceId: string
  scope: ExportScope
  selectedColumns: string[]
  filters: ExportFilter[]
  includeDetails: boolean
  locale: string
  timeZone: string
}

export type ExportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'

export interface ExportJob {
  uuid: string
  providerId: string
  resourceId: string
  scope: ExportScope
  status: ExportStatus
  processedRows: number
  resultRows: number
  byteSize: number
  errorCode?: string
  errorMessage?: string
  expiryAt: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
}
