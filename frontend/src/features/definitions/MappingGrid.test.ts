import { expect, it } from 'vitest'
import { assignDataObjectToMapping } from './MappingGrid'
import { DEFAULT_MAPPING } from './defaults'
import type { MappingContent } from './types'

it('cleans stale expression, filter and join references when a datastore is replaced', () => {
  const value: MappingContent = {
    ...DEFAULT_MAPPING,
    columnMappings: [
      { expression: { kind: 'CALL', function: 'UPPER', args: [{ kind: 'COLUMN', dataset: 'SOURCE_1', column: 'OLD' }] }, target: { object: 'TARGET', column: 'NAME' } },
      { expression: { kind: 'LITERAL', value: 'SOURCE_1.OLD' }, target: { object: 'TARGET', column: 'LABEL' } },
    ],
    filters: [
      { id: 'old', object: 'SOURCE_1', column: 'OLD', scope: 'SOURCE', operator: 'IS_NOT_NULL' },
      { id: 'valid', object: 'SOURCE_1', column: 'NAME', scope: 'SOURCE', operator: 'IS_NOT_NULL' },
    ],
    joins: [{ id: 'old-join', type: 'INNER', left: { object: 'SOURCE_1', column: 'OLD' }, right: { object: 'SOURCE_2', column: 'ID' } }],
  }
  const result = assignDataObjectToMapping(value, 'SOURCE_1', {
    model: { uuid: 'model', logicalSchemaUuid: 'logical', code: 'MODEL', status: 'AKTIF', name: 'Model', version: 1 },
    object: { uuid: 'object', modelUuid: 'model', code: 'TABLE', objectReference: 'APP.TABLE', type: 'TABLE', status: 'AKTIF', name: 'Table', version: 1 },
    snapshot: {
      uuid: 'snapshot', dataObjectUuid: 'object', physicalSchemaUuid: 'physical', connectionVersionUuid: 'version', fingerprint: 'hash', engineVersion: 'Oracle', discoveredAt: '2026-09-16T00:00:00Z', createdAt: '2026-09-16T00:00:00Z', serverProduced: true,
      columns: [{ reference: 'NAME', producerType: 'VARCHAR2', canonicalType: 'STRING', ordinal: 1, length: 255, nullable: true }], constraints: [],
    },
  })
  expect(result.columnMappings.map(row => row.target.column)).toEqual(['LABEL'])
  expect(result.filters.map(filter => filter.id)).toEqual(['valid'])
  expect(result.joins).toEqual([])
})

it('assigns a dropped model object to a source and removes mappings invalidated by its snapshot', () => {
  const result = assignDataObjectToMapping(DEFAULT_MAPPING, 'SOURCE_1', {
    model: { uuid: 'model', logicalSchemaUuid: 'logical', code: 'MODEL', status: 'AKTIF', name: 'Model', version: 1 },
    object: { uuid: 'object', modelUuid: 'model', code: 'TABLE', objectReference: 'APP.TABLE', type: 'TABLE', status: 'AKTIF', name: 'Table', version: 1 },
    snapshot: {
      uuid: 'snapshot', dataObjectUuid: 'object', physicalSchemaUuid: 'physical', connectionVersionUuid: 'connection-version', fingerprint: 'hash', engineVersion: 'Oracle', discoveredAt: '2026-09-16T00:00:00Z', createdAt: '2026-09-16T00:00:00Z', serverProduced: true,
      columns: [{ reference: 'NAME', producerType: 'VARCHAR2', canonicalType: 'STRING', ordinal: 1, length: 255, nullable: true }], constraints: [],
    },
  })

  expect(result.sources[0]).toEqual(expect.objectContaining({ dataObjectUuid: 'object', schemaSnapshotUuid: 'snapshot', alias: 'SRC_1' }))
  expect(result.columnMappings).toEqual([])
})
