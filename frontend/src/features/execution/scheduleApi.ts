import { apiRequest, jsonBody } from '../../core/api/client'
import type { CreateScheduleInput, Schedule } from './scheduleTypes'

const schedulesPath = (projectUuid: string) =>
  `/api/v1/projects/${encodeURIComponent(projectUuid)}/schedules`

export const scheduleApi = {
  list(projectUuid: string) {
    return apiRequest<Schedule[]>(schedulesPath(projectUuid))
  },
  get(projectUuid: string, scheduleUuid: string) {
    return apiRequest<Schedule>(`${schedulesPath(projectUuid)}/${encodeURIComponent(scheduleUuid)}`)
  },
  create(projectUuid: string, input: CreateScheduleInput) {
    return apiRequest<Schedule>(schedulesPath(projectUuid), { method: 'POST', ...jsonBody(input) })
  },
  pause(projectUuid: string, scheduleUuid: string, expectedVersion: number) {
    return apiRequest<Schedule>(`${schedulesPath(projectUuid)}/${encodeURIComponent(scheduleUuid)}/pause`, {
      method: 'POST', ...jsonBody({ expectedVersion }),
    })
  },
  resume(projectUuid: string, scheduleUuid: string, expectedVersion: number) {
    return apiRequest<Schedule>(`${schedulesPath(projectUuid)}/${encodeURIComponent(scheduleUuid)}/resume`, {
      method: 'POST', ...jsonBody({ expectedVersion }),
    })
  },
  remove(projectUuid: string, scheduleUuid: string, expectedVersion: number) {
    return apiRequest<void>(`${schedulesPath(projectUuid)}/${encodeURIComponent(scheduleUuid)}?expectedVersion=${expectedVersion}`, {
      method: 'DELETE',
    })
  },
}
