import type { CreateConnectionVersionRequest } from './api'

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
  credentialSecretReferenceUuid: string
  jndiName: string
}

export const initialConnectionVersionDraft: ConnectionVersionDraft = {
  mode: 'JDBC', host: '', port: '1521', identifierType: 'SERVICE_NAME',
  identifier: '', transport: 'TCP', credentialSecretReferenceUuid: '', jndiName: '',
}

export function validateConnectionVersionDraft(draft: ConnectionVersionDraft): string[] {
  if (draft.mode === 'JNDI') {
    return /^java:comp\/env\/jdbc\/[A-Za-z0-9_.-]{1,180}$/.test(draft.jndiName)
      ? []
      : ['jndiName']
  }
  const port = Number(draft.port)
  return [
    ...(!/^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(draft.host) || draft.host.includes('..') ? ['host'] : []),
    ...(!Number.isInteger(port) || port < 1 || port > 65535 ? ['port'] : []),
    ...(!/^[A-Za-z0-9_$#.-]{1,128}$/.test(draft.identifier) ? ['identifier'] : []),
    ...(!draft.credentialSecretReferenceUuid ? ['credential'] : []),
  ]
}

export function toCreateConnectionVersionRequest(draft: ConnectionVersionDraft): CreateConnectionVersionRequest {
  if (draft.mode === 'JNDI') {
    return {
      mode: 'JNDI', jndi: { name: draft.jndiName.trim() },
      policyVersion: 2, executionPolicy: {},
    }
  }
  return {
    mode: 'JDBC',
    jdbc: {
      host: draft.host.trim(), port: Number(draft.port),
      connectIdentifier: { type: draft.identifierType, value: draft.identifier.trim() },
      transport: draft.transport,
      credentialSecretReferenceUuid: draft.credentialSecretReferenceUuid,
    },
    policyVersion: 2, executionPolicy: {},
  }
}
