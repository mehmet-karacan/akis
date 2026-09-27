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
  producer?: {
    application: string
    version: string
    commit: string
  }
  includedSections?: string[]
}

export interface SelectedBundle {
  document: ProjectBundleDocument
  fileName: string
  size: number
  fingerprint: string
}

export type GlobalResourceType =
  | 'CONNECTION'
  | 'PHYSICAL_SCHEMA'
  | 'LOGICAL_SCHEMA'
  | 'ENVIRONMENT'

export type GlobalBindingMode = 'BIND_EXISTING' | 'CREATE_GLOBAL'

export interface GlobalBinding {
  type: GlobalResourceType
  sourceCode: string
  mode: GlobalBindingMode
  targetUuid?: string | null
  newCode?: string | null
  configuration?: unknown | null
}

export interface GlobalDependency {
  type: GlobalResourceType
  sourceCode: string
  provider?: string | null
  required: boolean
  mode?: GlobalBindingMode | null
  targetUuid?: string | null
  targetCode?: string | null
  resolved: boolean
  message: string
}

export interface TargetImportPlan {
  valid: boolean
  targetProjectUuid: string
  targetVersion: number
  bundleChecksum: string
  planDigest: string
  counts: BundleCounts
  changes: Record<string, number>
  globalDependencies: GlobalDependency[]
  issues: BundleIssue[]
}

export interface TargetImportResult {
  imported: boolean
  replayed: boolean
  targetProjectUuid: string
  targetVersion: number
  bundleChecksum: string
  planDigest: string
  counts: BundleCounts
}
