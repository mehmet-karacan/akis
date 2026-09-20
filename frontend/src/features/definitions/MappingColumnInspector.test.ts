import { expect, it } from 'vitest'
import { mapTargetFromSourceReference, resolveSourceColumnReference } from './MappingColumnInspector'
import { DEFAULT_MAPPING } from './defaults'

const columns = {
  SOURCE_1: [{ reference: 'CUSTOMER_ID', producerType: 'NUMBER', canonicalType: 'INTEGER', ordinal: 1, nullable: false }],
  TARGET: [{ reference: 'CUSTOMER_ID', producerType: 'NUMBER', canonicalType: 'INTEGER', ordinal: 1, nullable: false }],
}

it('resolves an alias.column source reference without case sensitivity', () => {
  const value = { ...DEFAULT_MAPPING, sources: DEFAULT_MAPPING.sources.map(source => source.id === 'SOURCE_1' ? { ...source, alias: 'src' } : source) }

  expect(resolveSourceColumnReference('SRC.customer_id', value, columns)).toEqual({ datasetId: 'SOURCE_1', column: 'CUSTOMER_ID' })
  expect(resolveSourceColumnReference('missing.customer_id', value, columns)).toBeNull()
})

it('replaces the existing mapping for a selected target column', () => {
  const result = mapTargetFromSourceReference(DEFAULT_MAPPING, 'TARGET', 'ID', { datasetId: 'SOURCE_1', column: 'CUSTOMER_ID' })

  expect(result.columnMappings).toEqual([{
    source: { object: 'SOURCE_1', column: 'CUSTOMER_ID' },
    target: { object: 'TARGET', column: 'ID' },
  }])
})
