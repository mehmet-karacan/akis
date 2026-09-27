export type ScheduleStatus = 'AKTIF' | 'ASKIDA'
export type ScheduleConflictPolicy = 'SKIP' | 'QUEUE'
export type ScheduleMisfirePolicy = 'SKIP' | 'RUN_ONCE'
export type SchedulePublicationPolicy = 'LATEST_ACTIVE' | 'PINNED'

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
  publicationPolicy: SchedulePublicationPolicy
  /** What would actually fire right now under the current policy; null if unresolved (e.g. a PINNED target that is no longer active). */
  resolvedPublicationUuid: string | null
  resolvedVersionNumber: number | null
  /** Set only when the fire poller auto-suspended this schedule (e.g. its PINNED publication stopped being active/approved). */
  lastErrorMessage: string | null
  nextFireTime: string | null
  lastFireTime: string | null
  startsAt: string | null
  endsAt: string | null
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
  publicationPolicy: SchedulePublicationPolicy
  desiredStatus: ScheduleStatus
  startsAt?: string | null
  endsAt?: string | null
}

export interface SchedulePreview {
  cronExpression: string
  normalizedExpression: string
  timeZone: string
  serverTime: string
  syntaxValid: boolean
  exhausted: boolean
  description: string
  descriptionParts: string[]
  nextOccurrences: string[]
  errors: string[]
}

export interface ScheduleTriggerEvent {
  id: number
  scheduledFor: string
  outcome: string
  occurredAt: string
  configuredPublicationUuid: string
  resolvedPublicationUuid: string | null
}
