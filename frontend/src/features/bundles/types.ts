export type ConflictPolicy = 'FAIL' | 'RENAME'

export interface BundleCounts {
  folders: number
  definitions: number
  drafts: number
  versions: number
}
export interface BundleIssue {
  path: string
  code: string
  message: string
}

export interface ValidationReport {
  valid: boolean
  counts: BundleCounts
  issues: BundleIssue[]
}

export interface ImportResult {
  imported: boolean
  dryRun: boolean
  projectCode: string
  projectUuid: string | null
  counts: BundleCounts
}

export interface ProjectBundleDocument {
  format: string
  formatVersion: number
  schemaVersion: number
  checksum: string
  exportedAt: string
  project: {
    code: string
    status: string
    name: string
    description: string | null
  }
  folders: unknown[]
  definitions: unknown[]
  topology: { sanitized: boolean; definitions: Record<string, unknown> }
}

export interface SelectedBundle {
  document: ProjectBundleDocument
  fileName: string
  size: number
  fingerprint: string
}
