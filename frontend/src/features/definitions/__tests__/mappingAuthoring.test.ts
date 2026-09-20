import { describe, expect, it } from 'vitest'
import { editMapping, initialSchemaVersion, migrateLegacyMapping, storeMapping } from '../mappingAuthoring'
import { DEFAULT_MAPPING } from '../defaults'

describe('mapping authoring storage contract', () => {
  it('uses the direct-reference mapping schema', () => {
    expect(initialSchemaVersion('MAPPING')).toBe(4)
    expect(initialSchemaVersion('PROCEDURE')).toBe(2)
    expect(initialSchemaVersion('KNOWLEDGE_MODULE')).toBe(1)
    expect(initialSchemaVersion('KNOWLEDGE_MODULE', { language: 'AKIS_KM/1' })).toBe(2)
  })

  it('persists direct catalog references without a separate binding model', () => {
    const content = structuredClone(DEFAULT_MAPPING)
    content.sources[0] = { ...content.sources[0]!, dataObjectUuid: 'object', schemaSnapshotUuid: 'snapshot' }
    expect(editMapping(storeMapping(content))).toEqual(content)
  })

  it('converts a legacy dataset draft without discarding its references', () => {
    const migrated = migrateLegacyMapping({
      datasets: [
        { id: 'SOURCE_1', role: 'SOURCE', ui: { name: 'SRC', dataObjectUuid: 'source-object', schemaSnapshotUuid: 'source-snapshot' } },
        { id: 'TARGET_1', role: 'TARGET', ui: { name: 'TGT', dataObjectUuid: 'target-object', schemaSnapshotUuid: 'target-snapshot' } },
      ],
      columnMappings: [{ source: { dataset: 'SOURCE_1', column: 'ID' }, target: { dataset: 'TARGET_1', column: 'ID' } }],
      writeStrategy: { kind: 'APPEND' },
    })
    expect(migrated.sources[0]).toMatchObject({ id: 'SOURCE_1', alias: 'SRC', dataObjectUuid: 'source-object' })
    expect(migrated.target).toMatchObject({ id: 'TARGET_1', alias: 'TGT', dataObjectUuid: 'target-object' })
    expect(migrated.columnMappings[0]).toEqual({ source: { object: 'SOURCE_1', column: 'ID' }, target: { object: 'TARGET_1', column: 'ID' } })
    expect(migrated).not.toHaveProperty('datasets')
    expect(migrated).not.toHaveProperty('writeStrategy')
  })
})
