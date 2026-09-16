import { describe, expect, it } from 'vitest'
import { editMapping, initialSchemaVersion, storeMapping } from '../mappingAuthoring'
import { DEFAULT_MAPPING } from '../defaults'

describe('mapping authoring storage contract', () => {
  it('creates mapping and procedure schema 2 without changing other types', () => {
    expect(initialSchemaVersion('MAPPING')).toBe(2)
    expect(initialSchemaVersion('PROCEDURE')).toBe(2)
    expect(initialSchemaVersion('KNOWLEDGE_MODULE')).toBe(1)
    expect(initialSchemaVersion('KNOWLEDGE_MODULE', { language: 'AKIS_KM/1' })).toBe(2)
  })
  it('round trips catalog hints without making them executable bindings', () => {
    const content = structuredClone(DEFAULT_MAPPING)
    content.datasets[0] = { ...content.datasets[0]!, dataObjectUuid: 'object', schemaSnapshotUuid: 'snapshot', ui: { x: 10 } }
    const original = structuredClone(content)
    const stored = storeMapping(content)
    expect(stored.datasets[0]).not.toHaveProperty('dataObjectUuid')
    expect(stored.datasets[0]?.ui).toEqual({ x: 10, name: 'Source', dataObjectUuid: 'object', schemaSnapshotUuid: 'snapshot' })
    expect(editMapping(stored).datasets[0]?.dataObjectUuid).toBe('object')
    expect(storeMapping(editMapping(stored))).toEqual(stored)
    expect(content).toEqual(original)
  })
  it('preserves unknown semantics for fail-closed backend validation', () => {
    const content = structuredClone(DEFAULT_MAPPING)
    content.datasets[0]!.unsafeSql = 'unexpected'
    expect(storeMapping(content).datasets[0]?.unsafeSql).toBe('unexpected')
    expect(storeMapping(content).writeStrategy.kind).toBe('APPEND')
  })
  it('clears catalog hints and refuses to discard malformed existing UI', () => {
    const content = editMapping(storeMapping(structuredClone(DEFAULT_MAPPING)))
    content.datasets[0]!.name = undefined
    expect(storeMapping(content).datasets[0]?.ui).not.toHaveProperty('name')
    content.datasets[0]!.ui = 'legacy-unrecognized'
    expect(() => storeMapping(content)).toThrow('ui must be an object')
  })
})
