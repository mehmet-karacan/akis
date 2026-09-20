import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MappingGrid } from './MappingGrid'
import { DEFAULT_MAPPING } from './defaults'
import { topologyApi, type SchemaSnapshot } from '../topology/api'
import type { MappingContent } from './types'

vi.mock('./i18n', () => ({ useDefinitionsI18n: () => ({ language: 'en', t: (key: string) => key }) }))
vi.mock('../topology/api', () => ({ topologyApi: { listModels: vi.fn(), listLogicalSchemas: vi.fn(), listDataObjects: vi.fn(), listSubmodels: vi.fn(), listSchemaSnapshots: vi.fn() } }))
vi.mock('./MappingKmOptions', () => ({ MappingKmOptions: () => null }))
vi.mock('./MappingDiagram', () => ({ MappingDiagram: ({ columns, onDropObject }: { columns: unknown; onDropObject(id: string, object: string): void }) => <><div aria-label="Diagram columns">{JSON.stringify(columns)}</div><button onClick={() => onDropObject('SOURCE_1', 'object')}>Drop Latest</button></> }))

const model = { uuid: 'model', name: 'Model', code: 'MODEL', logicalSchemaUuid: 'logical', status: 'AKTIF', version: 1 }
const object = { uuid: 'object', modelUuid: 'model', code: 'T', name: 'Table', objectReference: 'APP.T', type: 'TABLE', status: 'AKTIF', version: 1 }
const snapshot = (uuid: string, column: string): SchemaSnapshot => ({ uuid, dataObjectUuid: 'object', physicalSchemaUuid: 'physical', connectionVersionUuid: 'connection', fingerprint: uuid, engineVersion: 'Oracle', discoveredAt: '', createdAt: '', columns: [{ reference: column, producerType: 'NUMBER(10,0)', canonicalType: 'INTEGER', ordinal: 1, nullable: false }], constraints: [], serverProduced: true })
const older = snapshot('old', 'OLD_COLUMN'), latest = snapshot('new', 'NEW_COLUMN')
const content: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'SOURCE_1', alias: 'SRC', dataObjectUuid: 'object', schemaSnapshotUuid: 'old' }], columnMappings: [{ source: { object: 'SOURCE_1', column: 'OLD_COLUMN' }, target: { object: 'TARGET', column: 'ID' } }] }
const changed = vi.fn()
function Harness({ project = 'project', initial = content }: { project?: string; initial?: MappingContent }) {
  const [value, setValue] = useState(initial)
  return <MappingGrid projectUuid={project} value={value} onChange={next => { changed(next); setValue(next) }} />
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(topologyApi.listModels).mockResolvedValue([model])
  vi.mocked(topologyApi.listLogicalSchemas).mockResolvedValue([])
  vi.mocked(topologyApi.listSubmodels).mockResolvedValue([])
  vi.mocked(topologyApi.listDataObjects).mockResolvedValue([object])
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValue([latest, older])
})
it('shows pinned columns and changes the pin only on explicit reassignment, with Undo', async () => {
  render(<Harness />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  expect(screen.getByLabelText('Diagram columns')).not.toHaveTextContent('NEW_COLUMN')
  expect(screen.getByRole('status')).toHaveTextContent('Newer metadata')
  expect(changed).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Drop Latest' }))
  expect(changed.mock.calls.at(-1)?.[0].sources[0].schemaSnapshotUuid).toBe('new')
  expect(changed.mock.calls.at(-1)?.[0].columnMappings).toEqual([])
  expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('NEW_COLUMN')
  fireEvent.click(screen.getByRole('button', { name: /^undo$/ }))
  expect(changed.mock.calls.at(-1)?.[0]).toEqual(content)
  expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN')
})
it('refreshes catalog changes without substituting the new capture for the existing pin', async () => {
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValueOnce([older]).mockResolvedValueOnce([latest, older])
  render(<Harness />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  act(() => { window.dispatchEvent(new Event('akis:models-changed')) })
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Newer metadata'))
  expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN')
  expect(changed).not.toHaveBeenCalled()
})
it('keeps mappings intact and removes misleading column display if the pinned capture is unavailable', async () => {
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValue([latest])
  render(<Harness />)
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('metadata version used by this interface is unavailable'))
  expect(screen.getByLabelText('Diagram columns')).not.toHaveTextContent('NEW_COLUMN')
  expect(changed).not.toHaveBeenCalled()
})
it('clears old-project metadata immediately and discards a stale catalog response', async () => {
  let resolveOld!: (rows: SchemaSnapshot[]) => void
  vi.mocked(topologyApi.listSchemaSnapshots).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce([])
  const rendered = render(<Harness />)
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledOnce())
  rendered.rerender(<Harness project="other" />)
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unavailable'))
  await act(async () => { resolveOld([latest, older]) })
  expect(screen.getByLabelText('Diagram columns')).not.toHaveTextContent('OLD_COLUMN')
  expect(changed).not.toHaveBeenCalled()
})

const unmapped: MappingContent = { ...content, target: { id: 'T', alias: 'DEST', dataObjectUuid: 'object', schemaSnapshotUuid: 'old' }, columnMappings: [] }
it('proposes and applies a missing target row from pinned metadata', async () => {
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValue([older])
  render(<Harness initial={unmapped} />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  fireEvent.click(screen.getByRole('tab', { name: 'Column Mappings' }))
  fireEvent.click(screen.getByRole('button', { name: 'suggestMatches' }))
  fireEvent.click(screen.getByRole('button', { name: 'applySuggestions' }))
  expect(changed.mock.calls.at(-1)?.[0].columnMappings).toEqual([{ source: { object: 'SOURCE_1', column: 'OLD_COLUMN' }, target: { object: 'T', column: 'OLD_COLUMN' } }])
})
it('explains ambiguity without offering to apply an arbitrary source', async () => {
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValue([older])
  render(<Harness initial={{ ...unmapped, sources: [...unmapped.sources, { id: 'SOURCE_2', alias: 'OTHER', dataObjectUuid: 'object', schemaSnapshotUuid: 'old' }] }} />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  fireEvent.click(screen.getByRole('tab', { name: 'Column Mappings' }))
  fireEvent.click(screen.getByRole('button', { name: 'suggestMatches' }))
  expect(screen.getByRole('status')).toHaveTextContent('No unique, type-compatible matches')
  expect(screen.queryByRole('button', { name: 'applySuggestions' })).not.toBeInTheDocument()
  expect(changed).not.toHaveBeenCalled()
})
it('invalidates proposals after definition changes or metadata refresh', async () => {
  vi.mocked(topologyApi.listSchemaSnapshots).mockResolvedValue([older])
  const rendered = render(<MappingGrid projectUuid="project" value={unmapped} onChange={changed} />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  fireEvent.click(screen.getByRole('tab', { name: 'Column Mappings' }))
  fireEvent.click(screen.getByRole('button', { name: 'suggestMatches' }))
  expect(screen.getByRole('button', { name: 'applySuggestions' })).toBeInTheDocument()
  rendered.rerender(<MappingGrid projectUuid="project" value={{ ...unmapped }} onChange={changed} />)
  expect(screen.queryByRole('button', { name: 'applySuggestions' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'suggestMatches' }))
  act(() => { window.dispatchEvent(new Event('akis:models-changed')) })
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledTimes(2))
  expect(screen.queryByRole('button', { name: 'applySuggestions' })).not.toBeInTheDocument()
  expect(changed).not.toHaveBeenCalled()
})
it('keeps join identities distinct after removing the first join and adding again', async () => {
  const join = { type: 'INNER' as const, left: { object: 'SOURCE_1', column: 'OLD_COLUMN' }, right: { object: 'SOURCE_2', column: 'OLD_COLUMN' } }
  render(<Harness initial={{ ...unmapped, sources: [...unmapped.sources, { id: 'SOURCE_2', alias: 'OTHER', dataObjectUuid: 'object', schemaSnapshotUuid: 'old' }], joins: [{ ...join, id: 'JOIN_1' }, { ...join, id: 'JOIN_2' }] }} />)
  await waitFor(() => expect(screen.getByLabelText('Diagram columns')).toHaveTextContent('OLD_COLUMN'))
  fireEvent.click(screen.getByRole('tab', { name: 'Joins and Filters' }))
  fireEvent.click(screen.getAllByRole('button', { name: 'Remove join' })[0]!)
  fireEvent.click(screen.getByRole('button', { name: 'Add Join' }))
  expect(changed.mock.calls.at(-1)?.[0].joins.map((row: { id: string }) => row.id)).toEqual(['JOIN_2', 'JOIN_3'])
})
