import type { CreateConnectionVersionRequest, OracleDraftConnectionTestRequest } from './api'

export type ConnectionMode = 'JDBC' | 'JNDI'
export type IdentifierType = 'SERVICE_NAME' | 'SID'
export type Transport = 'TCP'

export interface ConnectionVersionDraft {
  mode: ConnectionMode
  host: string
  port: string
  identifierType: IdentifierType
  identifier: string
  transport: Transport
  credentialReferencePath: string
  username: string
  password: string
  jndiName: string
  connectTimeoutMs: string
  readTimeoutMs: string
  networkTimeoutMs: string
  queryTimeoutSeconds: string
}

export const initialConnectionVersionDraft: ConnectionVersionDraft = {
  mode: 'JDBC', host: '', port: '1521', identifierType: 'SERVICE_NAME',
  identifier: '', transport: 'TCP', credentialReferencePath: '', username: '', password: '', jndiName: '',
  connectTimeoutMs: '10000', readTimeoutMs: '30000', networkTimeoutMs: '30000', queryTimeoutSeconds: '300',
}

function validInteger(value: string, minimum: number, maximum: number) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum
}

function policyErrors(draft: ConnectionVersionDraft) {
  return [
    ...(!validInteger(draft.connectTimeoutMs, 1_000, 120_000) ? ['connectTimeoutMs'] : []),
    ...(!validInteger(draft.readTimeoutMs, 1_000, 300_000) ? ['readTimeoutMs'] : []),
    ...(!validInteger(draft.networkTimeoutMs, 1_000, 300_000) ? ['networkTimeoutMs'] : []),
    ...(!validInteger(draft.queryTimeoutSeconds, 1, 3_600) ? ['queryTimeoutSeconds'] : []),
  ]
}

export function validateConnectionVersionDraft(draft: ConnectionVersionDraft): string[] {
  if (draft.mode === 'JNDI') {
    return /^java:comp\/env\/jdbc\/[A-Za-z0-9_.-]{1,180}$/.test(draft.jndiName)
      ? policyErrors(draft)
      : ['jndiName']
  }
  const port = Number(draft.port)
  return [
    ...(!/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(draft.host) || draft.host.includes('..') ? ['host'] : []),
    ...(!Number.isInteger(port) || port < 1 || port > 65535 ? ['port'] : []),
    ...(!/^[A-Za-z0-9_$#.-]{1,128}$/.test(draft.identifier) ? ['identifier'] : []),
    ...(!/^[A-Z][A-Z0-9_]{1,199}$/.test(draft.credentialReferencePath) ? ['credential'] : []),
    ...policyErrors(draft),
  ]
}

export function validateOracleConnectionInput(draft: ConnectionVersionDraft): string[] {
  if (draft.mode === 'JNDI') return validateConnectionVersionDraft(draft)
  return [
    ...validateConnectionVersionDraft(draft).filter((field) => field !== 'credential'),
    ...(!draft.username.trim() ? ['username'] : []),
    ...(!draft.password ? ['password'] : []),
  ]
}

function executionPolicy(draft: ConnectionVersionDraft) {
  return {
    connectTimeoutMs: Number(draft.connectTimeoutMs),
    readTimeoutMs: Number(draft.readTimeoutMs),
    networkTimeoutMs: Number(draft.networkTimeoutMs),
    queryTimeoutSeconds: Number(draft.queryTimeoutSeconds),
  }
}

export function toCreateConnectionVersionRequest(draft: ConnectionVersionDraft): CreateConnectionVersionRequest {
  if (draft.mode === 'JNDI') {
    return {
      mode: 'JNDI', jndi: { name: draft.jndiName.trim() },
      policyVersion: 2, executionPolicy: executionPolicy(draft),
    }
  }
  return {
    mode: 'JDBC',
    jdbc: {
      host: draft.host.trim(), port: Number(draft.port),
      connectIdentifier: { type: draft.identifierType, value: draft.identifier.trim() },
      transport: draft.transport,
      credentialProvider: 'ENV',
      credentialReferencePath: draft.credentialReferencePath.trim(),
    },
    policyVersion: 2, executionPolicy: executionPolicy(draft),
  }
}

export function toOracleConnectionInput(draft: ConnectionVersionDraft): OracleDraftConnectionTestRequest {
  const policy = { policyVersion: 2 as const, executionPolicy: executionPolicy(draft) }
  if (draft.mode === 'JNDI') return { mode: 'JNDI', jndi: { name: draft.jndiName.trim() }, ...policy }
  return {
    mode: 'JDBC',
    jdbc: {
      host: draft.host.trim(), port: Number(draft.port),
      connectIdentifier: { type: draft.identifierType, value: draft.identifier.trim() },
      transport: 'TCP',
    },
    credentials: { username: draft.username.trim(), password: draft.password },
    ...policy,
  }
}
