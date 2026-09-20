import { expect, it } from 'vitest'
import { mappingCatalogEntry, mappingMetadataNotice, type MappingCatalogEntry } from './mappingCatalog'
import type { SchemaSnapshot } from '../topology/api'

const snapshot = (uuid: string): SchemaSnapshot => ({ uuid, dataObjectUuid: 'object', physicalSchemaUuid: 'physical', connectionVersionUuid: 'connection', fingerprint: uuid, engineVersion: 'Oracle', discoveredAt: '', createdAt: '', columns: [], constraints: [], serverProduced: true })
const latest = snapshot('latest'), pinned = snapshot('pinned')
const entry: MappingCatalogEntry = {
  model: { uuid: 'model', name: 'Model', code: 'MODEL', logicalSchemaUuid: 'logical', status: 'AKTIF', version: 1 },
  object: { uuid: 'object', modelUuid: 'model', code: 'T', name: 'Table', objectReference: 'APP.T', type: 'TABLE', status: 'AKTIF', version: 1 },
  snapshot: latest, snapshots: [latest, pinned],
}
const reference = { id: 'SOURCE_1', alias: 'SRC', dataObjectUuid: 'object', schemaSnapshotUuid: 'pinned' }
it('resolves the selected capture, not the most recent capture, without mutating the catalog', () => {
  expect(mappingCatalogEntry(reference, [entry])?.snapshot).toBe(pinned)
  expect(entry.snapshot).toBe(latest)
  expect(mappingMetadataNotice(reference, [entry], false)).toContain('Newer metadata')
  expect(mappingMetadataNotice({ ...reference, schemaSnapshotUuid: 'latest' }, [entry], false)).toBeUndefined()
})
it('does not fall back to another capture when a pinned version disappears', () => {
  const missing = { ...reference, schemaSnapshotUuid: 'unavailable' }
  expect(mappingCatalogEntry(missing, [entry])?.snapshot).toBeUndefined()
  expect(mappingMetadataNotice(missing, [entry], false)).toContain('Mappings are preserved')
  expect(mappingMetadataNotice(reference, [], false)).toContain('data object is unavailable')
})
it('keeps legacy unpinned previews explicit and explains absent metadata', () => {
  const unpinned = { ...reference, schemaSnapshotUuid: undefined }
  expect(mappingCatalogEntry(unpinned, [entry])?.snapshot).toBe(latest)
  expect(mappingMetadataNotice(unpinned, [entry], false)).toContain('No metadata version is assigned')
  expect(mappingMetadataNotice(unpinned, [{ ...entry, snapshot: undefined, snapshots: [] }], false)).toContain('Reverse Engineer')
  expect(mappingMetadataNotice({ id: 'EMPTY', alias: 'Empty' }, [], false)).toBeUndefined()
})
