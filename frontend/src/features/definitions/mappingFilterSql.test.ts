import { expect, it } from 'vitest'
import { mappingFilterSql, nextFilterId } from './mappingFilterSql'
import { DEFAULT_MAPPING } from './defaults'
import type { MappingFilter } from './types'
import { editMapping, storeMapping } from './mappingAuthoring'
import { removeMappingObject } from './mappingReferences'
import { mappingSqlText } from './mappingSqlText'

const value = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'SRC' }, { id: 'S2', alias: 'CUSTOMER' }] }
it('preserves string binding and LIKE escape semantics when opening legacy filters', () => {
  const filter: MappingFilter = { id: 'F', scope: 'SOURCE', object: 'S1', column: 'NAME', operator: 'LIKE', value: "A\\_%'X" }
  expect(mappingFilterSql(filter, value)).toBe("SRC.NAME LIKE 'A\\_%''X' ESCAPE '\\'")
  expect(mappingFilterSql({ ...filter, operator: 'IS_NULL' }, value)).toBe('SRC.NAME IS NULL')
  expect(mappingFilterSql({ ...filter, operator: 'GREATER_THAN', value: '10' }, value)).toBe("SRC.NAME > '10'")
  expect(filter.operator).toBe('LIKE')
})
it('retains free predicates across store/edit and cleans references to any removed source', () => {
  const filter: MappingFilter = { id: 'FILTER_1', scope: 'GLOBAL', object: 'S1', predicate: { kind: 'BINARY', operator: '=', left: { kind: 'COLUMN', dataset: 'S2', column: 'ID' }, right: { kind: 'LITERAL', value: 10 } } }
  const content = { ...value, filters: [filter] }
  expect(editMapping(storeMapping(content)).filters).toEqual([filter])
  expect(mappingFilterSql(filter, value)).toBe('(CUSTOMER.ID = 10)')
  expect(removeMappingObject(content, 'S2').filters).toEqual([])
  expect(nextFilterId([{ ...filter, id: 'FILTER_2' }, { ...filter, id: 'FILTER_3' }])).toBe('FILTER_1')
})
it('does not round numeric SQL constants through JavaScript numbers', () => {
  const exact = '9007199254740993.12345678901234567890'
  const expression = JSON.parse(JSON.stringify({ kind: 'NUMBER', value: exact }))
  expect(mappingSqlText(expression, {})).toBe(exact)
  expect(() => mappingSqlText({ kind: 'NUMBER', value: '1 OR 1=1' }, {})).toThrow()
})
