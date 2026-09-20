import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi, type DiscoveryResult } from '../topology/api'
import { MetadataImportPage } from './MetadataImportPage'
import { bindingFixture, connectionFixture, environmentFixture, physicalSchemaFixture } from '../topology/testFixtures'

const result: DiscoveryResult = { connectionUuid: 'connection', physicalSchemaUuid: 'physical', owner: 'APP', discoveredAt: '2026-01-01T00:00:00Z', truncated: false,
  tables: [{ owner: 'APP', name: 'DISCOVERED_TABLE', type: 'TABLE', columns: [], constraints: [] }] }
function Navigation() {
  const navigate = useNavigate()
  return <button onClick={() => navigate('/projects/p/models/second/import')}>Next Model</button>
}
function open() { return render(<MemoryRouter initialEntries={['/projects/p/models/first/import']}><Navigation /><Routes><Route path="/projects/:projectUuid/models/:modelUuid/import" element={<MetadataImportPage />} /></Routes></MemoryRouter>) }
beforeEach(async () => {
  vi.restoreAllMocks(); await i18n.changeLanguage('en')
  vi.spyOn(topologyApi, 'getModel').mockImplementation(async (_, uuid) => ({ uuid, name: `Model ${uuid}`, code: uuid.toUpperCase(), logicalSchemaUuid: 'logical', status: 'AKTIF', version: 1 }))
  vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([])
  vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([environmentFixture({ uuid: 'environment', code: 'TEST', name: 'Test' })])
  vi.spyOn(topologyApi, 'listBindings').mockResolvedValue([bindingFixture({ uuid: 'binding', logicalSchemaUuid: 'logical', environmentUuid: 'environment', physicalSchemaUuid: 'physical' })])
  vi.spyOn(topologyApi, 'listPhysicalSchemas').mockResolvedValue([physicalSchemaFixture({ uuid: 'physical', connectionUuid: 'connection', name: 'App', schemaName: 'APP' })])
  vi.spyOn(topologyApi, 'listConnections').mockResolvedValue([connectionFixture({ uuid: 'connection', code: 'DB', name: 'Database' })])
  vi.spyOn(topologyApi, 'discoverOracle').mockResolvedValue(result)
})

it('locks discovery context while pending and clears results when its scope changes', async () => {
  let resolve!: (value: DiscoveryResult) => void
  vi.mocked(topologyApi.discoverOracle).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  open()
  fireEvent.click(await screen.findByRole('button', { name: 'Fetch Objects From Source' }))
  expect(screen.getByRole('textbox')).toBeDisabled()
  expect(screen.getByRole('combobox', { name: 'Discovery environment' })).toBeDisabled()
  await act(async () => resolve(result))
  expect(await screen.findAllByText('DISCOVERED_TABLE', { selector: 'strong' })).toHaveLength(1)
  expect(screen.getByRole('textbox')).toBeEnabled()
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'OTHER_TABLE' } })
  expect(screen.queryByText('DISCOVERED_TABLE')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Import Selected/ })).not.toBeInTheDocument()
})

it('never silently runs standard discovery for a custom RKM model', async () => {
  vi.mocked(topologyApi.getModel).mockResolvedValue({ uuid: 'first', name: 'Custom Model', code: 'CUSTOM', logicalSchemaUuid: 'logical', status: 'AKTIF', version: 1, reverseMode: 'CUSTOM_RKM', rkmDefinitionUuid: 'rkm' })
  open()
  const discover = await screen.findByRole('button', { name: 'Fetch Objects From Source' })
  expect(discover).toBeDisabled()
  expect(screen.getByText(/Custom RKM execution is not connected yet/)).toBeVisible()
  fireEvent.click(discover)
  expect(topologyApi.discoverOracle).not.toHaveBeenCalled()
})

it('does not bring delayed discovery results into another model', async () => {
  let resolve!: (value: DiscoveryResult) => void
  vi.mocked(topologyApi.discoverOracle).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  open()
  fireEvent.click(await screen.findByRole('button', { name: 'Fetch Objects From Source' }))
  fireEvent.click(screen.getByRole('button', { name: 'Next Model' }))
  await screen.findByRole('link', { name: 'Model second' })
  await act(async () => resolve(result))
  expect(screen.queryByText('DISCOVERED_TABLE')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Fetch Objects From Source' })).toBeEnabled()
})

it('shows discovery errors in a toast and removes previous results before retry', async () => {
  vi.mocked(topologyApi.discoverOracle).mockResolvedValueOnce(result).mockRejectedValueOnce(new Error('Discovery unavailable'))
  open()
  fireEvent.click(await screen.findByRole('button', { name: 'Fetch Objects From Source' }))
  await screen.findAllByText('DISCOVERED_TABLE', { selector: 'strong' })
  fireEvent.click(screen.getByRole('button', { name: 'Fetch Objects From Source' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Discovery unavailable'))
  expect(screen.queryByText('DISCOVERED_TABLE')).not.toBeInTheDocument()
})

it('keeps partial import progress and retries only the unfinished object without recreating it', async () => {
  const tables = ['ONE', 'TWO'].map(name => ({ ...result.tables[0]!, name }))
  vi.mocked(topologyApi.discoverOracle).mockResolvedValue({ ...result, tables })
  const catalog: Awaited<ReturnType<typeof topologyApi.listDataObjects>> = []
  vi.mocked(topologyApi.listDataObjects).mockImplementation(async () => [...catalog])
  const create = vi.spyOn(topologyApi, 'createDataObject').mockImplementation(async (_, modelUuid, body) => {
    const object = { uuid: String(body.name), modelUuid, code: String(body.code), name: String(body.name), objectReference: String(body.objectReference), type: 'TABLO', version: 1, status: 'AKTIF' }
    catalog.push(object); return object
  })
  const capture = vi.spyOn(topologyApi, 'captureOracleSchemaSnapshot').mockResolvedValueOnce({} as never).mockRejectedValueOnce(new Error('Capture failed')).mockResolvedValueOnce({} as never)
  open()
  fireEvent.click(await screen.findByRole('button', { name: 'Fetch Objects From Source' }))
  for (const checkbox of await screen.findAllByRole('checkbox')) fireEvent.click(checkbox)
  fireEvent.click(screen.getByRole('button', { name: /Save Selected|Import Selected|Import selected|Save selected/ }))
  await waitFor(() => expect(screen.getByText(/Metadata saved: 1 \/ 2/)).toBeInTheDocument())
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Capture failed'))
  expect(screen.getAllByRole('checkbox')[0]).not.toBeChecked()
  expect(screen.getAllByRole('checkbox')[1]).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: /Save Selected|Import Selected|Import selected|Save selected/ }))
  await screen.findByText(/Metadata saved: 1 \/ 1/)
  expect(create).toHaveBeenCalledTimes(2)
  expect(capture.mock.calls.map(call => call[3])).toEqual(['ONE', 'TWO', 'TWO'])
  expect(screen.getAllByRole('checkbox').every(checkbox => !(checkbox as HTMLInputElement).checked)).toBe(true)
})
