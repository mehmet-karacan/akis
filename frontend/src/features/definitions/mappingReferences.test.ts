import { describe, expect, it } from 'vitest'
import { DEFAULT_MAPPING } from './defaults'
import { expressionColumnsValid, removeMappingObject } from './mappingReferences'
import type { MappingContent } from './types'

describe('mapping object reference cleanup', () => {
  it('walks nested column references without confusing literals or similar object IDs', () => {
    const valid = (object: string) => object !== 'SOURCE_1'
    expect(expressionColumnsValid({ kind: 'LITERAL', value: 'SOURCE_1.ID' }, valid)).toBe(true)
    expect(expressionColumnsValid({ kind: 'COLUMN', dataset: 'OTHER_SOURCE_1', column: 'ID' }, valid)).toBe(true)
    expect(expressionColumnsValid({ kind: 'CALL', function: 'UPPER', args: [{ kind: 'COLUMN', dataset: 'SOURCE_1', column: 'NAME' }] }, valid)).toBe(false)
    const cyclic: Record<string, unknown> = { kind: 'CALL', function: 'UPPER' }
    cyclic.args = [cyclic]
    expect(expressionColumnsValid(cyclic, valid)).toBe(false)
  })

  it('removes dependent mappings, joins and filters but preserves independent expressions', () => {
    const value: MappingContent = {
      ...DEFAULT_MAPPING,
      sources: [...DEFAULT_MAPPING.sources, { id: 'SOURCE_2', alias: 'OTHER' }],
      columnMappings: [
        ...DEFAULT_MAPPING.columnMappings,
        { expression: { kind: 'CALL', function: 'UPPER', args: [{ kind: 'COLUMN', dataset: 'SOURCE_1', column: 'NAME' }] }, target: { object: 'TARGET', column: 'NAME' } },
        { expression: { kind: 'LITERAL', value: 'SOURCE_1.ID' }, target: { object: 'TARGET', column: 'LABEL' } },
      ],
      filters: [
        { id: 'one', scope: 'SOURCE', object: 'SOURCE_1', column: 'ID', operator: 'IS_NOT_NULL' },
        { id: 'two', scope: 'SOURCE', object: 'SOURCE_2', column: 'ID', operator: 'IS_NOT_NULL' },
      ],
      joins: [{ id: 'join', type: 'LEFT', left: { object: 'SOURCE_1', column: 'ID' }, right: { object: 'SOURCE_2', column: 'ID' } }],
    }
    const before = structuredClone(value)
    const next = removeMappingObject(value, 'SOURCE_1')
    expect(next.sources.map(source => source.id)).toEqual(['SOURCE_2'])
    expect(next.columnMappings.map(row => row.target.column)).toEqual(['LABEL'])
    expect(next.filters.map(filter => filter.id)).toEqual(['two'])
    expect(next.joins).toEqual([])
    expect(value).toEqual(before)
  })

  it('retains an empty target node for assigning its replacement', () => {
    const next = removeMappingObject({ ...DEFAULT_MAPPING, target: { ...DEFAULT_MAPPING.target, dataObjectUuid: 'old', schemaSnapshotUuid: 'old-snapshot' } }, 'TARGET')
    expect(next.target).toEqual({ id: 'TARGET', alias: 'TGT' })
    expect(next.columnMappings).toEqual([])
  })
})
