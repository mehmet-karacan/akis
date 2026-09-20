import { describe, expect, it } from 'vitest'
import { bindingFixture, connectionFixture, physicalSchemaFixture } from '../topology/testFixtures'
import { resolveProcedureContext } from './ResolvedContextSummary'

describe('procedure context resolution', () => {
  it('resolves the physical schema and connection behind a logical schema binding', () => {
    const result = resolveProcedureContext('logical', 'test',
      [bindingFixture({ uuid: 'b', logicalSchemaUuid: 'logical', environmentUuid: 'test', physicalSchemaUuid: 'p' })],
      [physicalSchemaFixture({ uuid: 'p', connectionUuid: 'c', schemaName: 'SCOTT' })],
      [connectionFixture({ uuid: 'c', code: 'SKY', name: 'SKY' })])
    expect(result.physicalSchema?.schemaName).toBe('SCOTT')
    expect(result.connection?.code).toBe('SKY')
  })
})
