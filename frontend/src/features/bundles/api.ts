import { apiRequest, jsonBody } from '../../core/api/client'
import type {
  ConflictPolicy,
  GlobalBinding,
  ImportResult,
  ProjectBundleDocument,
  TargetImportPlan,
  TargetImportResult,
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

  planTargetImport(
    targetProjectUuid: string,
    document: ProjectBundleDocument,
    globalBindings: GlobalBinding[],
  ) {
    return apiRequest<TargetImportPlan>(
      `/api/v1/projects/${encodeURIComponent(targetProjectUuid)}/bundle/plan`,
      {
        method: 'POST',
        ...jsonBody({ bundle: document, globalBindings }),
      },
    )
  },

  importIntoTarget(
    targetProjectUuid: string,
    payload: {
      bundle: ProjectBundleDocument
      globalBindings: GlobalBinding[]
      planDigest: string
      targetVersion: number
    },
    idempotencyKey: string,
  ) {
    return apiRequest<TargetImportResult>(
      `/api/v1/projects/${encodeURIComponent(targetProjectUuid)}/bundle/import`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        ...jsonBody(payload),
      },
    )
  },
}
