import { ApiProblem, apiRequest, jsonBody } from '../../core/api/client'
import type { Run, RunEvent } from './types'

const runsPath = (projectUuid: string) =>
  `/api/v1/projects/${encodeURIComponent(projectUuid)}/runs`

export const executionApi = {
  listRuns(projectUuid: string) {
    return apiRequest<Run[]>(runsPath(projectUuid))
  },
  getRun(projectUuid: string, runUuid: string) {
    return apiRequest<Run>(`${runsPath(projectUuid)}/${encodeURIComponent(runUuid)}`)
  },
  listEvents(projectUuid: string, runUuid: string) {
    return apiRequest<RunEvent[]>(
      `${runsPath(projectUuid)}/${encodeURIComponent(runUuid)}/events`,
    )
  },
  startRun(projectUuid: string, publicationUuid: string, idempotencyKey: string) {
    return apiRequest<Run>(runsPath(projectUuid), {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      ...jsonBody({ publicationUuid }),
    })
  },
  cancelRun(projectUuid: string, runUuid: string) {
    return apiRequest<Run>(`${runsPath(projectUuid)}/${encodeURIComponent(runUuid)}/cancel`, {
      method: 'POST',
    })
  },
}

export function createIdempotencyKey() {
  return crypto.randomUUID()
}

export function isExecutionDisabled(error: unknown) {
  return error instanceof ApiProblem
    && error.status === 503
    && error.code === 'EXECUTION_REQUESTS_DISABLED'
}
