import { expect, it } from 'vitest'
import { canConnectMapping, connectMapping } from './MappingDiagram'
import { DEFAULT_MAPPING } from './defaults'

it('allows only source-to-target connections and prevents duplicate target assignments', () => {
  const connection = { source: 'SOURCE_1', target: 'TARGET_1', sourceHandle: 'ID', targetHandle: 'NAME' }
  expect(canConnectMapping(DEFAULT_MAPPING, connection)).toBe(true)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, targetHandle: 'ID' })).toBe(false)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, source: 'TARGET_1' })).toBe(false)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, sourceHandle: null })).toBe(false)
})

it('creates a mapping when a source column is dropped on an empty target column', () => {
  const value = { ...DEFAULT_MAPPING, columnMappings: [] }
  const connection = { source: 'SOURCE_1', target: 'TARGET_1', sourceHandle: 'NAME', targetHandle: 'NAME' }
  const columns = { SOURCE_1: ['ID', 'NAME'], TARGET_1: ['ID', 'NAME'] }

  const result = connectMapping(value, connection, columns)

  expect(result.columnMappings).toEqual([{
    source: { dataset: 'SOURCE_1', column: 'NAME' },
    target: { dataset: 'TARGET_1', column: 'NAME' },
  }])
})

it('ignores unknown columns and drops on a target column that is already mapped', () => {
  const columns = { SOURCE_1: ['ID', 'NAME'], TARGET_1: ['ID', 'NAME'] }
  const duplicate = { source: 'SOURCE_1', target: 'TARGET_1', sourceHandle: 'NAME', targetHandle: 'ID' }
  const unknown = { ...duplicate, targetHandle: 'UNKNOWN' }

  expect(connectMapping(DEFAULT_MAPPING, duplicate, columns)).toBe(DEFAULT_MAPPING)
  expect(connectMapping(DEFAULT_MAPPING, unknown, columns)).toBe(DEFAULT_MAPPING)
})
