import { expect, it } from 'vitest'
import type { SchemaSnapshot } from '../topology/api'
import { DEFAULT_MAPPING } from './defaults'
import { applyColumnSuggestions, nextJoinId, suggestColumnMappings } from './mappingSuggestions'
import type { MappingContent } from './types'

const column = (reference: string, canonicalType = 'INTEGER'): SchemaSnapshot['columns'][number] => ({ reference, canonicalType, producerType: canonicalType, ordinal: 1, nullable: true })
const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'A', alias: 'FIRST' }, { id: 'B', alias: 'SECOND' }], target: { id: 'T', alias: 'DEST' }, columnMappings: [] }
const columns = { A: [column('ID'), column('NAME', 'STRING')], B: [column('CODE')], T: [column('ID'), column('NAME', 'STRING'), column('CODE')] }

it('suggests all unmapped target columns, including targets with no existing row', () => {
  const suggestions = suggestColumnMappings(value, columns)
  expect(suggestions).toHaveLength(3)
  expect(suggestions[2]?.source).toEqual({ object: 'B', column: 'CODE' })
  expect(applyColumnSuggestions(value, columns, suggestions).columnMappings).toEqual(suggestions)
  expect(value.columnMappings).toEqual([])
})
it('does not choose the first same-named source even if only one type is compatible', () => {
  const ambiguous = { ...columns, B: [column('ID', 'STRING')] }
  expect(suggestColumnMappings(value, ambiguous).map(row => row.target.column)).toEqual(['NAME'])
})
it('rejects incompatible types, unknown metadata and missing target columns', () => {
  expect(suggestColumnMappings(value, { A: [column('ID', 'STRING')], T: [column('ID')] })).toEqual([])
  expect(suggestColumnMappings(value, { A: [column('ID', '')], T: [column('ID')] })).toEqual([])
  expect(suggestColumnMappings(value, { A: [column('ID')] })).toEqual([])
})
it('preserves expressions and existing source mappings and fills an empty row without duplication', () => {
  const content: MappingContent = { ...value, columnMappings: [
    { target: { object: 'T', column: 'ID' }, expression: { kind: 'LITERAL', value: 10 } },
    { target: { object: 'T', column: 'NAME' }, source: { object: 'B', column: 'LABEL' } },
    { target: { object: 'T', column: 'CODE' } },
  ] }
  const result = applyColumnSuggestions(content, columns, suggestColumnMappings(content, columns))
  expect(result.columnMappings).toHaveLength(3)
  expect(result.columnMappings.slice(0, 2)).toEqual(content.columnMappings.slice(0, 2))
  expect(result.columnMappings[2]?.source).toEqual({ object: 'B', column: 'CODE' })
})
it('revalidates suggestions after metadata or mappings change rather than applying row indexes', () => {
  const suggestions = suggestColumnMappings(value, columns)
  const reordered: MappingContent = { ...value, columnMappings: [
    { target: { object: 'T', column: 'CODE' } },
    { target: { object: 'T', column: 'ID' }, expression: { kind: 'LITERAL', value: 4 } },
  ] }
  const result = applyColumnSuggestions(reordered, { ...columns, A: [column('ID')] }, suggestions)
  expect(result.columnMappings).toEqual([{ target: { object: 'T', column: 'CODE' }, source: { object: 'B', column: 'CODE' } }, reordered.columnMappings[1]])
})
it('matches casing without a browser-locale dependency and preserves original identifiers', () => {
  const result = suggestColumnMappings(value, { A: [column('id')], T: [column('ID')] })
  expect(result).toEqual([{ source: { object: 'A', column: 'id' }, target: { object: 'T', column: 'ID' } }])
})
it('does not propose a repair for duplicate target mappings', () => {
  const row = { target: { object: 'T', column: 'ID' } }
  expect(suggestColumnMappings({ ...value, columnMappings: [row, row] }, { A: [column('ID')], T: [column('ID')] })).toEqual([])
})
it('allocates a noncolliding join id after an earlier join is removed', () => {
  const join = { type: 'INNER' as const, left: { object: 'A', column: 'ID' }, right: { object: 'B', column: 'ID' } }
  expect(nextJoinId([])).toBe('JOIN_1')
  expect(nextJoinId([{ ...join, id: 'JOIN_2' }])).toBe('JOIN_3')
  expect(nextJoinId([{ ...join, id: 'JOIN_3' }, { ...join, id: 'JOIN_4' }])).toBe('JOIN_5')
})
