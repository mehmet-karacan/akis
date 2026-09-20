import { expect, it } from 'vitest'
import { DEFAULT_MAPPING } from './defaults'
import { mappingColumnCompletions, mappingSqlText } from './mappingSqlText'
import { expressionColumnsValid } from './mappingReferences'

const value = { ...DEFAULT_MAPPING, sources: [{ id: 'SOURCE_1', alias: 'SRC' }] }
const columns = { SOURCE_1: [{ reference: 'CREATED_AT', producerType: 'DATE', canonicalType: 'DATE', ordinal: 1, nullable: false }] }
it('suggests catalog columns after an alias inside a function expression, not inside a string', () => {
  expect(mappingColumnCompletions('TO_CHAR(src.C', 13, value, columns)).toEqual({ from: 12, options: [{ label: 'CREATED_AT', type: 'property', detail: 'DATE' }] })
  expect(mappingColumnCompletions("'src.C", 6, value, columns)).toBeNull()
  expect(mappingColumnCompletions("'O''Brien src.C", 14, value, columns)).toBeNull()
  expect(mappingColumnCompletions('UNKNOWN.', 8, value, columns)).toBeNull()
})
it('renders SQL literals and current aliases from stable object references', () => {
  expect(mappingSqlText({ kind: 'CALL', function: 'TO_CHAR', args: [{ kind: 'COLUMN', dataset: 'SOURCE_1', column: 'CREATED_AT' }, { kind: 'LITERAL', value: "YYYY 'day'" }] }, { SOURCE_1: 'RENAMED' }))
    .toBe("TO_CHAR(RENAMED.CREATED_AT, 'YYYY ''day''')")
})
it('walks CASE, predicates and arithmetic when cleaning up deleted source references', () => {
  const column = { kind: 'COLUMN', dataset: 'SOURCE_1', column: 'CREATED_AT' }
  const expression = { kind: 'CASE', branches: [{ when: { kind: 'UNARY', operator: 'IS NOT NULL', argument: column }, then: { kind: 'LITERAL', value: 'SOURCE_1.ID' } }], else: { kind: 'LITERAL', value: null } }
  expect(expressionColumnsValid(expression, () => true)).toBe(true)
  expect(expressionColumnsValid(expression, () => false)).toBe(false)
  expect(mappingSqlText(expression, { SOURCE_1: 'SRC' })).toContain('WHEN (SRC.CREATED_AT IS NOT NULL)')
})
