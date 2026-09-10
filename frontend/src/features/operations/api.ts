import { apiRequest, jsonBody } from '../../core/api/client'
import type {
  ApprovalDecision,
  ApprovalResult,
  IdentityUser,
  Membership,
  ProjectRole,
  Publication,
} from './types'

const projectPath = (projectUuid: string) =>
  `/api/v1/projects/${encodeURIComponent(projectUuid)}`

export const operationsApi = {
  listPublications(projectUuid: string) {
    return apiRequest<Publication[]>(`${projectPath(projectUuid)}/publications`)
  },
  getPublication(projectUuid: string, publicationUuid: string) {
    return apiRequest<Publication>(
      `${projectPath(projectUuid)}/publications/${encodeURIComponent(publicationUuid)}`,
    )
  },
  createPublication(projectUuid: string, scenarioUuid: string, environmentUuid: string) {
    return apiRequest<Publication>(`${projectPath(projectUuid)}/publications`, {
      method: 'POST',
      ...jsonBody({ scenarioUuid, environmentUuid }),
    })
  },
  decidePublication(
    projectUuid: string,
    publicationUuid: string,
    decision: ApprovalDecision,
    reason?: string,
  ) {
    return apiRequest<ApprovalResult>(
      `${projectPath(projectUuid)}/publications/${encodeURIComponent(publicationUuid)}/approvals`,
      { method: 'POST', ...jsonBody({ decision, reason: reason?.trim() || null }) },
    )
  },
  listUsers() {
    return apiRequest<IdentityUser[]>('/api/v1/identity/users')
  },
  createUser(input: Pick<IdentityUser, 'issuer' | 'subject' | 'name' | 'email'>) {
    return apiRequest<IdentityUser>('/api/v1/identity/users', {
      method: 'POST',
      ...jsonBody(input),
    })
  },
  listMemberships(projectUuid: string) {
    return apiRequest<Membership[]>(`${projectPath(projectUuid)}/memberships`)
  },
  createMembership(
    projectUuid: string,
    input: {
      userUuid: string
      role: ProjectRole
      startsAt: string | null
      endsAt: string | null
    },
  ) {
    return apiRequest<Membership>(`${projectPath(projectUuid)}/memberships`, {
      method: 'POST',
      ...jsonBody(input),
    })
  },
}

