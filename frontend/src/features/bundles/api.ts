import { apiRequest, jsonBody } from '../../core/api/client'
import type {
  ConflictPolicy,
  ImportResult,
  ProjectBundleDocument,
  ValidationReport,
} from './types'

export const bundleApi = {
  exportProject(projectUuid: string) {
    return apiRequest<ProjectBundleDocument>(
      `/api/v1/projects/${encodeURIComponent(projectUuid)}/bundle/export`,
    )
  },

  validate(document: ProjectBundleDocument) {
    return apiRequest<ValidationReport>('/api/v1/project-bundles/validate', {
      method: 'POST',
      ...jsonBody(document),
    })
  },

  importProject(document: ProjectBundleDocument, conflict: ConflictPolicy, dryRun: boolean) {
    const query = new URLSearchParams({ conflict, dryRun: String(dryRun) })
    return apiRequest<ImportResult>(`/api/v1/project-bundles/import?${query.toString()}`, {
      method: 'POST',
      ...jsonBody(document),
    })
  },
}
