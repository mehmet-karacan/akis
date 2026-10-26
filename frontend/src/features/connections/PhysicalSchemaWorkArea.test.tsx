import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../../core/i18n'
import { apiRequest } from '../../core/api/client'
import { physicalSchemaFixture } from '../topology/testFixtures'
import { PhysicalSchemaDetailDialog } from './PhysicalSchemaDetailDialog'

vi.mock('../../core/api/client', async importOriginal => ({ ...await importOriginal<Record<string, unknown>>(), apiRequest: vi.fn() }))
const endpoint = '/api/v1/projects/project/physical-schemas/schema/work-area-policy'
const policy = { enabled: false, allowSameSchema: false, maxObjects: 10, maxRowsPerRun: 100000, maxBytesPerRun: 268435456, retentionHours: 24 }
const item = physicalSchemaFixture({ uuid: 'schema', connectionUuid: 'pg', schemaName: 'SKY_DIT_PERF', databaseType: 'POSTGRESQL' })
function open(canManage = true) {
  return render(<MemoryRouter initialEntries={['/projects/project']}><Routes><Route path="/projects/:projectUuid" element={<PhysicalSchemaDetailDialog projectUuid="project" connectionUuid="pg" item={item} canManage={canManage} onClose={() => {}} onChanged={() => {}} />} /></Routes></MemoryRouter>)
}
beforeEach(async () => {
  vi.clearAllMocks()
  await i18n.changeLanguage('en')
  vi.mocked(apiRequest).mockImplementation(async path => path === endpoint ? { policy, version: 7 } : [])
})

it('loads without mutation and requests automatic resources when saving editable policy settings with optimistic version', async () => {
  open()
  expect(await screen.findByRole('heading', { name: 'Work Area Policy' })).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('spinbutton', { name: 'Maximum work objects' })).toHaveValue(10))
  expect(screen.queryByRole('spinbutton', { name: 'Maximum rows per run' })).not.toBeInTheDocument()
  expect(screen.queryByRole('spinbutton', { name: 'Maximum bytes per run' })).not.toBeInTheDocument()
  expect(screen.getByText(/application manages resource use automatically/i)).toBeInTheDocument()
  expect(vi.mocked(apiRequest).mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
  fireEvent.click(screen.getByRole('switch', { name: 'Enable work area' }))
  fireEvent.click(screen.getByRole('switch', { name: 'Allow work tables in target schema' }))
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Maximum work objects' }), { target: { value: '20' } })
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Retention hours' }), { target: { value: '48' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save Work Area Policy' }))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(endpoint, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ expectedVersion: 7, policy: { ...policy, enabled: true, allowSameSchema: true, maxObjects: 20, retentionHours: 48 }, automaticResources: true }) })))
  expect(await screen.findByRole('status')).toHaveTextContent('Work area policy saved.')
})

it('does not expose policy mutation to a read-only operator', async () => {
  open(false)
  await screen.findByRole('heading', { name: 'Work Area Policy' })
  expect(screen.queryByRole('button', { name: 'Save Work Area Policy' })).not.toBeInTheDocument()
  expect(screen.getByRole('switch', { name: 'Enable work area' })).toBeDisabled()
})

it('shows a stale-version failure without claiming that settings were saved', async () => {
  vi.mocked(apiRequest).mockImplementation(async (path, init) => {
    if (init?.method === 'PUT') throw new Error('Settings changed; reload required.')
    return path === endpoint ? { policy, version: 7 } : []
  })
  open()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save Work Area Policy' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Save Work Area Policy' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Settings changed; reload required.')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it.each(['Maximum work objects', 'Retention hours'])('prevents zero or non-integer %s from being submitted', async name => {
  open()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save Work Area Policy' })).toBeEnabled())
  fireEvent.change(screen.getByRole('spinbutton', { name }), { target: { value: '0' } })
  expect(screen.getByRole('button', { name: 'Save Work Area Policy' })).toBeDisabled()
  fireEvent.change(screen.getByRole('spinbutton', { name }), { target: { value: '1.5' } })
  expect(screen.getByRole('button', { name: 'Save Work Area Policy' })).toBeDisabled()
})

it('lets the server replace old row and byte budgets instead of validating hidden fields', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === endpoint ? { policy: { ...policy, maxRowsPerRun: 0, maxBytesPerRun: 0 }, version: 7 } : [])
  open()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save Work Area Policy' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Save Work Area Policy' }))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(endpoint, expect.objectContaining({ method: 'PUT', body: JSON.stringify({ expectedVersion: 7, policy: { ...policy, maxRowsPerRun: 0, maxBytesPerRun: 0 }, automaticResources: true }) })))
})
