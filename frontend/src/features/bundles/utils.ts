import type { ProjectBundleDocument, SelectedBundle } from './types'

export const MAX_BUNDLE_BYTES = 10 * 1024 * 1024
export const BUNDLE_FORMAT = 'akis.project-bundle'

export type FilePreflightError =
  | 'invalidExtension'
  | 'emptyFile'
  | 'fileTooLarge'
  | 'invalidJson'
  | 'invalidFormat'

export function safeBundleFileName(projectCode: string) {
  const normalized = projectCode
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-zA-Z0-9._-]+/g, '-')
    .replace(/^[._-]+|[._-]+$/g, '')
    .slice(0, 80)
  return `${normalized || 'project'}-bundle-v2.json`
}

export function isBundleDocument(value: unknown): value is ProjectBundleDocument {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const document = value as Record<string, unknown>
  const project = document.project
  const topology = document.topology
  return document.format === BUNDLE_FORMAT
    && document.formatVersion === 2
    && document.schemaVersion === 2
    && typeof document.checksum === 'string'
    && typeof document.exportedAt === 'string'
    && Boolean(project)
    && typeof project === 'object'
    && !Array.isArray(project)
    && typeof (project as Record<string, unknown>).code === 'string'
    && typeof (project as Record<string, unknown>).name === 'string'
    && Array.isArray(document.folders)
    && Array.isArray(document.definitions)
    && Boolean(topology)
    && typeof topology === 'object'
    && !Array.isArray(topology)
    && typeof (topology as Record<string, unknown>).sanitized === 'boolean'
    && Boolean((topology as Record<string, unknown>).definitions)
    && typeof (topology as Record<string, unknown>).definitions === 'object'
}

export async function readBundleFile(file: File): Promise<SelectedBundle> {
  if (!file.name.toLocaleLowerCase('en-US').endsWith('.json')) throw new Error('invalidExtension')
  if (file.size === 0) throw new Error('emptyFile')
  if (file.size > MAX_BUNDLE_BYTES) throw new Error('fileTooLarge')

  let value: unknown
  try {
    value = JSON.parse(await file.text()) as unknown
  } catch {
    throw new Error('invalidJson')
  }
  if (!isBundleDocument(value)) throw new Error('invalidFormat')

  return {
    document: value,
    fileName: file.name,
    size: file.size,
    fingerprint: `${file.name}:${file.size}:${file.lastModified}:${value.checksum}`,
  }
}

export function downloadJson(document: ProjectBundleDocument, projectCode: string) {
  const blob = new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = window.document.createElement('a')
  anchor.href = url
  anchor.download = safeBundleFileName(projectCode)
  anchor.rel = 'noopener'
  anchor.click()
  URL.revokeObjectURL(url)
}

export function formatFileSize(bytes: number, locale: string) {
  if (bytes < 1024) return `${bytes} B`
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(bytes / 1024)} KB`
}
