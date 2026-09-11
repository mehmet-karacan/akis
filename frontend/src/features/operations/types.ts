export type PublicationStatus = 'ONAY_BEKLIYOR' | 'AKTIF' | 'IPTAL' | string
export type ApprovalDecision = 'ONAY' | 'RED' | 'GERI_CEK'
export type ProjectRole = 'PROJE_YONETICISI' | 'GELISTIRICI' | 'IZLEYICI'

export interface Publication {
  uuid: string
  scenarioUuid: string
  definitionUuid: string
  definitionVersionUuid: string
  environmentUuid: string
  environmentCode: string
  environmentRisk: string
  publicationNumber: number
  status: PublicationStatus
  releaseHash: string
  dependencySummary: string
  physicalManifest: unknown
  publishedAt: string | null
  createdAt: string
  version: number
}

export interface Approval {
  uuid: string
  publicationUuid: string
  actorUuid: string
  actorName: string
  decision: ApprovalDecision
  decidedAt: string
  reason: string | null
}

export interface ApprovalResult {
  publication: Publication
  approval: Approval
}

export interface ProcedureSourcePreflight {
  publicationUuid: string
  publicationStatus: string
  sourceTaskId: string
  physicalIdentity: string
  observedRowCount: number
  maximumRows: number
  payloadByteCount: number
  payloadHash: string
  durationMs: number
  sourceReadOnly: boolean
  targetSessionOpened: boolean
}

export interface ProcedureTargetPreflight {
  publicationUuid: string
  publicationStatus: string
  targetTaskId: string
  physicalIdentity: string
  databaseUniqueName: string
  containerName: string
  targetIdentityHash: string
  currentUser: string
  ownsTarget: boolean
  canTruncate: boolean
  canInsert: boolean
  canExecuteDbmsStats: boolean
  durationMs: number
  targetReadOnly: boolean
  sourceSessionOpened: boolean
}

export interface ProcedurePilotVerification {
  publicationUuid: string
  sourceRowCount: number
  targetRowCount: number
  sourcePayloadHash: string
  targetPayloadHash: string
  matches: boolean
  sourceReadOnly: boolean
  targetReadOnly: boolean
  durationMs: number
}

export interface IdentityUser {
  uuid: string
  issuer: string
  subject: string
  status: string
  name: string
  email: string | null
  createdAt: string
}

export interface ProjectRoleView {
  uuid: string
  code: string
}

export interface Membership {
  uuid: string
  projectUuid: string
  userUuid: string
  status: string
  startsAt: string | null
  endsAt: string | null
  version: number
  roles: ProjectRoleView[]
}
