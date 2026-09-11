// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getTopologyCopy } from './copy'
import {
  compatibleDataObjects,
  compatibleModels,
  DiscoverySnapshotPanel,
  exactObjectReference,
} from './DiscoverySnapshotPanel'
import {
  topologyApi,
  type DataObject,
  type DiscoveryResult,
  type DiscoveryTable,
  type Model,
  type SchemaBinding,
  type SchemaSnapshot,
} from './api'

afterEach(() => vi.restoreAllMocks())

const table: DiscoveryTable = {
  owner: 'TTBP',
  name: 'HAKEDIS_TIPI',
  type: 'TABLE',
  columns: [{
    name: 'ID', jdbcType: 2, producerType: 'NUMBER', canonicalType: 'INTEGER',
    executionCapability: 'TRANSFER_SUPPORTED', ordinal: 1, nullable: false,
  }],
  constraints: [],
}

const discovery: DiscoveryResult = {
  connectionVersionUuid: 'version',
  physicalSchemaUuid: 'physical',
  owner: 'TTBP',
  discoveredAt: '2026-09-11T17:00:00Z',
  truncated: false,
  tables: [table],
}

const models: Model[] = [
  { uuid: 'linked-model', logicalSchemaUuid: 'logical', code: 'MODEL', status: 'AKTIF', name: 'Linked model', version: 1 },
  { uuid: 'other-model', logicalSchemaUuid: 'other', code: 'OTHER', status: 'AKTIF', name: 'Other model', version: 1 },
  { uuid: 'inactive-model', logicalSchemaUuid: 'logical', code: 'OLD', status: 'PASIF', name: 'Inactive model', version: 1 },
]

const bindings: SchemaBinding[] = [{
  uuid: 'binding', logicalSchemaUuid: 'logical', environmentUuid: 'environment',
  physicalSchemaUuid: 'physical', connectionVersionUuid: 'version', status: 'AKTIF', version: 1,
}]

const objects: DataObject[] = [
  { uuid: 'other-table', modelUuid: 'linked-model', code: 'OTHER', objectReference: 'OTHER_TABLE', type: 'TABLO', status: 'AKTIF', name: 'Other table', version: 1 },
  { uuid: 'exact-table', modelUuid: 'linked-model', code: 'HAKEDIS', objectReference: 'HAKEDIS_TIPI', type: 'TABLO', status: 'AKTIF', name: 'Hakediş tipi', version: 1 },
  { uuid: 'inactive-table', modelUuid: 'linked-model', code: 'OLD', objectReference: 'HAKEDIS_TIPI', type: 'TABLO', status: 'PASIF', name: 'Old table', version: 1 },
  { uuid: 'view', modelUuid: 'linked-model', code: 'VIEW', objectReference: 'HAKEDIS_TIPI', type: 'VIEW', status: 'AKTIF', name: 'View', version: 1 },
]

const snapshot: SchemaSnapshot = {
  uuid: 'snapshot-uuid', dataObjectUuid: 'exact-table', physicalSchemaUuid: 'physical',
  connectionVersionUuid: 'version', fingerprint: '1234567890123456789012345678901234567890',
  engineVersion: 'Oracle 19c', discoveredAt: '2026-09-11T17:05:00Z', createdAt: '2026-09-11T17:05:01Z',
  columns: [], constraints: [], serverProduced: true,
}

const baseProps = {
  projectUuid: 'project', connectionUuid: 'connection', connectionLabel: 'SKY',
  connectionVersionUuid: 'version', connectionVersionLabel: 'v1 · SKY',
  physicalSchemaUuid: 'physical', physicalSchemaLabel: 'TTBP', discovery, table,
  models, bindings, preferredModelUuid: 'other-model', copy: getTopologyCopy('en'), locale: 'en-GB',
}

describe('discovery schema snapshot flow', () => {
  it('filters by the active physical/version binding and active table objects', () => {
    expect(compatibleModels(models, bindings, 'physical', 'version').map((model) => model.uuid)).toEqual(['linked-model'])
    expect(compatibleModels(models, bindings, 'physical', 'another-version')).toEqual([])
    expect(compatibleDataObjects(objects).map((object) => object.uuid)).toEqual(['other-table', 'exact-table'])
    expect(exactObjectReference(table, '"hakedis_tipi"')).toBe(true)
    expect(exactObjectReference(table, 'TTBP.HAKEDIS_TIPI')).toBe(false)
  })

  it('loads candidates independently and preselects the exact object reference', async () => {
    vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue(objects)

    render(<DiscoverySnapshotPanel {...baseProps} />)
    fireEvent.click(screen.getByRole('button', { name: 'Save schema snapshot' }))

    await waitFor(() => expect(topologyApi.listDataObjects).toHaveBeenCalledWith('project', 'linked-model'))
    expect(screen.getByLabelText('Model')).toHaveValue('linked-model')
    expect(screen.getByLabelText('Data object')).toHaveValue('exact-table')
    expect(screen.getByRole('option', { name: /Hakediş tipi.*Recommended/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Old table/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /View/ })).not.toBeInTheDocument()
  })

  it('prevents duplicate capture and renders immutable evidence on success', async () => {
    vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue(objects)
    let resolveCapture!: (value: SchemaSnapshot) => void
    const capture = vi.spyOn(topologyApi, 'captureOracleSchemaSnapshot').mockImplementation(
      () => new Promise((resolve) => { resolveCapture = resolve }),
    )

    render(<DiscoverySnapshotPanel {...baseProps} />)
    fireEvent.click(screen.getByRole('button', { name: 'Save schema snapshot' }))
    const confirm = await screen.findByRole('button', { name: 'Confirm and save' })
    await waitFor(() => expect(confirm).toBeEnabled())
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(capture).toHaveBeenCalledOnce()
    expect(capture).toHaveBeenCalledWith('project', 'connection', 'version', 'physical', 'exact-table')
    resolveCapture(snapshot)

    expect(await screen.findByText('Schema snapshot saved')).toBeInTheDocument()
    expect(screen.getByText('snapshot-uuid')).toBeInTheDocument()
    expect(screen.getByTitle(snapshot.fingerprint)).toHaveTextContent('123456789012…34567890')
  })
})
