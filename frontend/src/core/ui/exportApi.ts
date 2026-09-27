import { apiRequest, jsonBody } from '../api/client'
import type { ExportJob, ExportRequest } from './exportTypes'

const exportsPath = (projectUuid: string) =>
  `/api/v1/projects/${encodeURIComponent(projectUuid)}/exports`
const globalExportsPath = '/api/v1/exports'

export const exportApi = {
  createJob(projectUuid: string, request: ExportRequest) {
    return apiRequest<ExportJob>(exportsPath(projectUuid), {
      method: 'POST',
      ...jsonBody(request),
    })
  },

  createGlobalJob(request: ExportRequest) {
    return apiRequest<ExportJob>(globalExportsPath, {
      method: 'POST',
      ...jsonBody(request),
    })
  },

  getStatus(projectUuid: string, exportUuid: string) {
    return apiRequest<ExportJob>(`${exportsPath(projectUuid)}/${encodeURIComponent(exportUuid)}`)
  },

  downloadUrl(projectUuid: string, exportUuid: string) {
    return `${exportsPath(projectUuid)}/${encodeURIComponent(exportUuid)}/download`
  },

  async download(projectUuid: string, exportUuid: string): Promise<{ blob: Blob; filename: string }> {
    const path = this.downloadUrl(projectUuid, exportUuid)
    const response = await fetch(path, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      const text = await response.text().catch(() => 'Download failed')
      throw new Error(text)
    }
    const disposition = response.headers.get('content-disposition') ?? ''
    const match = /filename="([^"]+)"/.exec(disposition)
    const filename = match?.[1] ?? 'export.json'
    const blob = await response.blob()
    return { blob, filename }
  },

  cancel(projectUuid: string, exportUuid: string) {
    return apiRequest<unknown>(`${exportsPath(projectUuid)}/${encodeURIComponent(exportUuid)}`, {
      method: 'DELETE',
    })
  },

  getGlobalStatus(exportUuid: string) {
    return apiRequest<ExportJob>(`${globalExportsPath}/${encodeURIComponent(exportUuid)}`)
  },

  globalDownloadUrl(exportUuid: string) {
    return `${globalExportsPath}/${encodeURIComponent(exportUuid)}/download`
  },

  cancelGlobal(exportUuid: string) {
    return apiRequest<unknown>(`${globalExportsPath}/${encodeURIComponent(exportUuid)}`, { method: 'DELETE' })
  },
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
