import { describe, expect, it } from 'vitest'
import { bindingFixture, connectionFixture, environmentFixture, logicalSchemaFixture, physicalSchemaFixture } from '../topology/testFixtures'
import { buildLogicalSchemaCatalog, compatiblePhysicalSchemas, mappingLabel, mappingState } from './logicalCatalog'

const environments = [environmentFixture({ uuid: 'test', code: 'TEST' }), environmentFixture({ uuid: 'prod', code: 'PROD' })]
const physical = [
  physicalSchemaFixture({ uuid: 'p-sky', connectionUuid: 'sky', schemaName: 'TTBP' }),
  physicalSchemaFixture({ uuid: 'p-pg', connectionUuid: 'pg', schemaName: 'public', databaseType: 'POSTGRESQL' }),
]
const connections = [connectionFixture({ uuid: 'sky', code: 'SKY' }), connectionFixture({ uuid: 'pg', code: 'PG', databaseType: 'POSTGRESQL' })]

describe('logical schema catalog', () => {
  it('resolves one mapping row per environment and reports how complete the schema is', () => {
    const item = buildLogicalSchemaCatalog(
      [logicalSchemaFixture({ uuid: 'orders' })], environments, physical, connections,
      [bindingFixture({ uuid: 'b', logicalSchemaUuid: 'orders', environmentUuid: 'test', physicalSchemaUuid: 'p-sky' })])[0]!
    expect(item.mappings.map((m) => mappingLabel(m, 'none'))).toEqual(['TEST → SKY / TTBP', 'PROD → none'])
    expect(item.mappedCount).toBe(1)
    expect(mappingState(item)).toBe('partial')
  })

  it('offers only physical schemas of the same provider', () => {
    expect(compatiblePhysicalSchemas('POSTGRESQL', physical).map((p) => p.uuid)).toEqual(['p-pg'])
    expect(compatiblePhysicalSchemas(null, physical)).toHaveLength(2)
  })
})
