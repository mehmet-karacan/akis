export type ScheduleStatus = 'AKTIF' | 'ASKIDA'
export type ScheduleConflictPolicy = 'SKIP' | 'QUEUE'
export type ScheduleMisfirePolicy = 'SKIP' | 'RUN_ONCE'

export interface Schedule {
  uuid: string
  kod: string
  ad: string
  publicationUuid: string
  cronExpression: string
  timeZone: string
  status: ScheduleStatus
  conflictPolicy: ScheduleConflictPolicy
  misfirePolicy: ScheduleMisfirePolicy
  nextFireTime: string | null
  lastFireTime: string | null
  version: number
}

export interface CreateScheduleInput {
  kod: string
  ad: string
  publicationUuid: string
  cronExpression: string
  timeZone: string
  conflictPolicy: ScheduleConflictPolicy
  misfirePolicy: ScheduleMisfirePolicy
}
