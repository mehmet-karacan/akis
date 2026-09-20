import { expect, it } from 'vitest'
import { typesCompatible } from './mappingColumnCompatibility'

it('treats same-family types as compatible', () => {
  expect(typesCompatible('VARCHAR2', 'CHAR')).toBe(true)
  expect(typesCompatible('NUMBER', 'INTEGER')).toBe(true)
  expect(typesCompatible('DATE', 'TIMESTAMP')).toBe(true)
})

it('rejects cross-family mappings without explicit conversion', () => {
  expect(typesCompatible('NUMBER', 'VARCHAR2')).toBe(false)
  expect(typesCompatible('DATE', 'NUMBER')).toBe(false)
  expect(typesCompatible('CLOB', 'DATE')).toBe(false)
})

it('treats a missing type as undetermined and therefore compatible', () => {
  expect(typesCompatible(undefined, 'NUMBER')).toBe(true)
  expect(typesCompatible('NUMBER', undefined)).toBe(true)
  expect(typesCompatible(undefined, undefined)).toBe(true)
})

it('is case-insensitive and preserves native type labels', () => {
  expect(typesCompatible('varchar2', 'Char')).toBe(true)
  expect(typesCompatible('Number(10,0)', 'INTEGER')).toBe(true)
  expect(typesCompatible('TIMESTAMP(6)', 'date')).toBe(true)
})

it('keeps distinct numeric and temporal sub-families separate', () => {
  expect(typesCompatible('FLOAT', 'DECIMAL')).toBe(true)
  expect(typesCompatible('INTEGER', 'NUMBER')).toBe(true)
  expect(typesCompatible('TIME', 'TIMESTAMP')).toBe(true)
  expect(typesCompatible('NUMBER', 'DATE')).toBe(false)
})
