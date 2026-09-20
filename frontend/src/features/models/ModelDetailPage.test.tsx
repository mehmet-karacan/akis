import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi, type Model, type SchemaSnapshot } from '../topology/api'
import { ModelDetailPage } from './ModelDetailPage'

const access = vi.hoisted(() => ({ discover: true }))
vi.mock('../../core/auth/ProjectAccessContext', () => ({ useProjectAccess: () => ({ can: (permission: string) => permission === 'KATALOG_KESFET' && access.discover }) }))
vi.mock('../../core/ui/useRecordAudit', () => ({ useRecordAudit: () => ({ records: {}, state: 'ready' }) }))

const model = (uuid: string): Model => ({ uuid, logicalSchemaUuid: 'schema', code: uuid.toUpperCase(), name: `Model ${uuid}`, status: 'AKTIF', version: 1 })
function Navigation() {
  const navigate = useNavigate()
  return <><button onClick={() => navigate('/projects/p/models/second')}>Next Model</button><button onClick={() => navigate('/projects/p/models/first?object=view')}>Open View</button></>
}
function open(path = '/projects/p/models/first') {
  return render(<MemoryRouter initialEntries={[path]}><Navigation /><Routes><Route path="/projects/:projectUuid/models/:modelUuid" element={<ModelDetailPage />} /></Routes></MemoryRouter>)
}
beforeEach(async () => {
  vi.restoreAllMocks(); access.discover = true
  await i18n.changeLanguage('en')
  vi.spyOn(topologyApi, 'getModel').mockImplementation(async (_, uuid) => model(uuid))
  vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([])
  vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([{ uuid: 'schema', name: 'Logical Schema A', code: 'SCHEMA_A', status: 'AKTIF' }])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([
    { uuid: 'table', modelUuid: 'first', code: 'T', name: 'Orders', objectReference: 'ORDERS', type: 'TABLE', status: 'AKTIF', version: 1 },
    { uuid: 'view', modelUuid: 'first', code: 'V', name: 'Order Summary', objectReference: 'V_ORDERS', type: 'VIEW', status: 'AKTIF', version: 1 },
  ])
  vi.spyOn(topologyApi, 'listSchemaSnapshots').mockResolvedValue([])
})

it('uses one catalog heading and keeps the detail dialog closed until a data store is selected', async () => {
  open()
  await screen.findByRole('heading', { name: 'Model first' })
  expect(screen.getAllByRole('heading', { name: 'Data Store List' })).toHaveLength(1)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.queryByText('No data objects yet')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Edit: Orders' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'View: Orders' })).toBeInTheDocument()
})

it('preserves object identity and opens details with double-click in card view', async () => {
  const { container } = open()
  await screen.findByText('Orders')
  fireEvent.click(screen.getByRole('radio', { name: 'Cards' }))
  const card = [...container.querySelectorAll('.ui-grid-record')].find(item => item.textContent?.includes('Orders'))!
  expect(card.querySelector('header')).toHaveTextContent('Orders')
  fireEvent.doubleClick(card)
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledWith('p', 'table'))
  expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
})

it('does not show stale metadata after changing the selected object', async () => {
  let resolve!: (value: SchemaSnapshot[]) => void
  vi.mocked(topologyApi.listSchemaSnapshots).mockImplementationOnce(() => new Promise(done => { resolve = done })).mockResolvedValueOnce([])
  open('/projects/p/models/first?object=table')
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledWith('p', 'table'))
  fireEvent.click(screen.getByRole('button', { name: 'Open View' }))
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledWith('p', 'view'))
  await act(async () => resolve([{ uuid: 'old', discoveredAt: '2026-01-01T00:00:00Z', engineVersion: 'STALE_ENGINE', columns: [] } as unknown as SchemaSnapshot]))
  expect(screen.getByRole('heading', { name: 'Order Summary' })).toBeInTheDocument()
  expect(screen.queryByText('STALE_ENGINE')).not.toBeInTheDocument()
})

it('isolates late model requests from the next model route', async () => {
  let resolve!: (value: Model) => void
  vi.mocked(topologyApi.getModel).mockImplementationOnce(() => new Promise(done => { resolve = done })).mockResolvedValueOnce(model('second'))
  open()
  await waitFor(() => expect(topologyApi.getModel).toHaveBeenCalledWith('p', 'first'))
  fireEvent.click(screen.getByRole('button', { name: 'Next Model' }))
  await screen.findByRole('heading', { name: 'Model second' })
  await act(async () => resolve(model('first')))
  expect(screen.queryByRole('heading', { name: 'Model first' })).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Model second' })).toBeInTheDocument()
})

it('does not keep a previous model visible when the next route fails and supports retry', async () => {
  vi.mocked(topologyApi.getModel).mockResolvedValueOnce(model('first')).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(model('second'))
  open()
  await screen.findByRole('heading', { name: 'Model first' })
  fireEvent.click(screen.getByRole('button', { name: 'Next Model' }))
  const retry = await screen.findByRole('button', { name: /Try Again|Retry/ })
  expect(screen.queryByRole('heading', { name: 'Model first' })).not.toBeInTheDocument()
  fireEvent.click(retry)
  expect(await screen.findByRole('heading', { name: 'Model second' })).toBeInTheDocument()
})

it('shows a missing selection explicitly and hides metadata refresh without discovery permission', async () => {
  access.discover = false
  open('/projects/p/models/first?object=missing')
  expect(await screen.findByText('Data Store Not Found')).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Reverse Engineer' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Refresh metadata:/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('radio', { name: 'Table' }))
  const row = screen.getByText('Orders').closest('tr')!
  expect(within(row).getByRole('button', { name: 'View: Orders' })).toBeInTheDocument()
  fireEvent.keyDown(row, { key: 'Enter' })
  await waitFor(() => expect(topologyApi.listSchemaSnapshots).toHaveBeenCalledWith('p', 'table'))
  expect(await screen.findByRole('heading', { name: 'Orders' })).toBeInTheDocument()
})
