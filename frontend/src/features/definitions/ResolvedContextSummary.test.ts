import { describe, expect, it } from 'vitest'
import { resolveProcedureContext } from './ResolvedContextSummary'

describe('procedure context resolution', () => {
  it('resolves the exact pinned connection revision', () => {
    const result = resolveProcedureContext('logical', 'test', [{ uuid: 'b', logicalSchemaUuid: 'logical', environmentUuid: 'test', physicalSchemaUuid: 'p', connectionVersionUuid: 'v2', status: 'AKTIF', version: 1 }], [{ uuid: 'p', connectionUuid: 'c', code: 'SCOTT', schemaReference: 'SCOTT', status: 'AKTIF', name: 'SCOTT', version: 1 }], [{ uuid: 'c', code: 'SKY', databaseType: 'ORACLE', status: 'AKTIF', name: 'SKY', version: 1 }], [{ uuid: 'v1', versionNumber: 1, mode: 'JDBC', policyVersion: 2, createdAt: '', lifecycleStatus: 'ACTIVE', lifecycleVersion: 1, runtimeCapability: 'EXECUTABLE' }, { uuid: 'v2', versionNumber: 2, mode: 'JDBC', policyVersion: 2, createdAt: '', lifecycleStatus: 'TESTED', lifecycleVersion: 1, runtimeCapability: 'EXECUTABLE' }])
    expect(result.version?.uuid).toBe('v2')
    expect(result.connection?.code).toBe('SKY')
  })
})
