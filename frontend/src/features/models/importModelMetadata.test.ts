import { expect, it, vi } from 'vitest'
import type { DataObject, DiscoveryTable } from '../topology/api'
import { importModelMetadata, type MetadataImportPorts } from './importModelMetadata'

const table = (name: string): DiscoveryTable => ({ owner: 'APP', name, type: 'TABLE', columns: [], constraints: [] })
const object = (name: string): DataObject => ({ uuid: `object-${name}`, modelUuid: 'model', name, code: name, objectReference: name, type: 'TABLO', status: 'AKTIF', version: 1 })
const ports = (overrides: Partial<MetadataImportPorts> = {}): MetadataImportPorts => ({
  listObjects: vi.fn().mockResolvedValue([]), createObject: vi.fn(async entry => object(entry.name)),
  captureSnapshot: vi.fn().mockResolvedValue({}), isActive: () => true, onRegistered: vi.fn(), onImported: vi.fn(), ...overrides,
})

it('recovers registration followed by snapshot failure without creating a duplicate', async () => {
  const catalog: DataObject[] = []
  const api = ports({ listObjects: vi.fn(async () => catalog), createObject: vi.fn(async entry => { const created = object(entry.name); catalog.push(created); return created }),
    captureSnapshot: vi.fn().mockRejectedValueOnce(new Error('snapshot failed')).mockResolvedValueOnce({}) })
  await expect(importModelMetadata([table('ONE')], api)).rejects.toThrow('snapshot failed')
  expect(api.onImported).not.toHaveBeenCalled()
  expect(await importModelMetadata([table('ONE')], api)).toBe(1)
  expect(api.listObjects).toHaveBeenCalledTimes(2)
  expect(api.createObject).toHaveBeenCalledTimes(1)
  expect(api.captureSnapshot).toHaveBeenCalledTimes(2)
})

it('reconciles an uncertain create response from the catalog on the next attempt', async () => {
  const catalog: DataObject[] = []
  const api = ports({ listObjects: vi.fn(async () => [...catalog]), createObject: vi.fn(async entry => { catalog.push(object(entry.name)); throw new Error('response lost') }) })
  await expect(importModelMetadata([table('ONE')], api)).rejects.toThrow('response lost')
  expect(api.captureSnapshot).not.toHaveBeenCalled()
  expect(await importModelMetadata([table('ONE')], api)).toBe(1)
  expect(api.createObject).toHaveBeenCalledTimes(1)
})

it('preserves progress for completed tables and stops at the first failed snapshot', async () => {
  const api = ports({ captureSnapshot: vi.fn().mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('failed')) })
  await expect(importModelMetadata([table('ONE'), table('TWO'), table('THREE')], api)).rejects.toThrow('failed')
  expect(api.onImported).toHaveBeenCalledExactlyOnceWith(table('ONE'), 1)
  expect(api.createObject).toHaveBeenCalledTimes(2)
  expect(api.captureSnapshot).toHaveBeenCalledTimes(2)
})

it('does not start a snapshot or another create after leaving the page during registration', async () => {
  let active = true
  const api = ports({ isActive: () => active, createObject: vi.fn(async entry => { active = false; return object(entry.name) }) })
  expect(await importModelMetadata([table('ONE'), table('TWO')], api)).toBe(0)
  expect(api.onRegistered).toHaveBeenCalledExactlyOnceWith(object('ONE'))
  expect(api.captureSnapshot).not.toHaveBeenCalled()
  expect(api.createObject).toHaveBeenCalledTimes(1)
})

it('finishes observation of an in-flight snapshot but starts no further table after leaving', async () => {
  let active = true
  const api = ports({ isActive: () => active, captureSnapshot: vi.fn(async () => { active = false; return {} }) })
  expect(await importModelMetadata([table('ONE'), table('TWO')], api)).toBe(1)
  expect(api.onImported).toHaveBeenCalledExactlyOnceWith(table('ONE'), 1)
  expect(api.createObject).toHaveBeenCalledTimes(1)
})

it('fails closed when catalog refresh fails or matching objects are ambiguous', async () => {
  const unavailable = ports({ listObjects: vi.fn().mockRejectedValue(new Error('offline')) })
  await expect(importModelMetadata([table('ONE')], unavailable)).rejects.toThrow('offline')
  expect(unavailable.createObject).not.toHaveBeenCalled()
  const ambiguous = ports({ listObjects: vi.fn().mockResolvedValue([object('ONE'), { ...object('ONE'), uuid: 'duplicate' }]) })
  await expect(importModelMetadata([table('ONE')], ambiguous)).rejects.toThrow('METADATA_OBJECT_AMBIGUOUS')
  expect(ambiguous.createObject).not.toHaveBeenCalled()
  expect(ambiguous.captureSnapshot).not.toHaveBeenCalled()
})

it('does not mutate the catalog array or perform work without a selection/active session', async () => {
  const frozen = Object.freeze([]) as unknown as DataObject[]
  const api = ports({ listObjects: vi.fn().mockResolvedValue(frozen) })
  expect(await importModelMetadata([table('ONE')], api)).toBe(1)
  expect(frozen).toHaveLength(0)
  const idle = ports({ isActive: () => false })
  expect(await importModelMetadata([table('ONE')], idle)).toBe(0)
  expect(idle.listObjects).not.toHaveBeenCalled()
  expect(await importModelMetadata([], api)).toBe(0)
  expect(api.listObjects).toHaveBeenCalledTimes(1)
})
