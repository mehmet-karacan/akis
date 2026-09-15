import { expect, it } from 'vitest'
import { canConnectMapping } from './MappingDiagram'
import { DEFAULT_MAPPING } from './defaults'
it('allows only source-to-target connections and prevents duplicate target assignments', () => {
  const connection = { source: 'SOURCE_1', target: 'TARGET_1', sourceHandle: 'ID', targetHandle: 'NAME' }
  expect(canConnectMapping(DEFAULT_MAPPING, connection)).toBe(true)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, targetHandle: 'ID' })).toBe(false)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, source: 'TARGET_1' })).toBe(false)
  expect(canConnectMapping(DEFAULT_MAPPING, { ...connection, sourceHandle: null })).toBe(false)
})
