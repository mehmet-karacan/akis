import { expect, it } from 'vitest'
import { assignDataObjectToMapping } from './MappingGrid'
import { DEFAULT_MAPPING } from './defaults'

it('assigns a dropped model object to a dataset and removes mappings invalidated by its snapshot', () => {
  const result = assignDataObjectToMapping(DEFAULT_MAPPING, 'SOURCE_1', {
    model: { uuid: 'model', logicalSchemaUuid: 'logical', code: 'MODEL', status: 'AKTIF', name: 'Model', version: 1 },
    object: { uuid: 'object', modelUuid: 'model', code: 'TABLE', objectReference: 'APP.TABLE', type: 'TABLE', status: 'AKTIF', name: 'Table', version: 1 },
    snapshot: {
      uuid: 'snapshot', dataObjectUuid: 'object', physicalSchemaUuid: 'physical', connectionVersionUuid: 'connection-version', fingerprint: 'hash', engineVersion: 'Oracle', discoveredAt: '2026-09-16T00:00:00Z', createdAt: '2026-09-16T00:00:00Z', serverProduced: true,
      columns: [{ reference: 'NAME', producerType: 'VARCHAR2', canonicalType: 'STRING', ordinal: 1, length: 255, nullable: true }], constraints: [],
    },
  })

  expect(result.datasets[0]).toEqual(expect.objectContaining({ dataObjectUuid: 'object', schemaSnapshotUuid: 'snapshot', name: 'Table' }))
  expect(result.columnMappings).toEqual([])
})
