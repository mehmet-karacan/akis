import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectAccessProvider } from '../../core/auth/ProjectAccessContext'
import i18n from '../../core/i18n'
import { topologyApi } from '../topology/api'
import { EnvironmentsPage } from './EnvironmentsPage'
import { bindingFixture, connectionFixture, environmentFixture, logicalSchemaFixture, physicalSchemaFixture } from '../topology/testFixtures'

function open(permissions: string[] = ['BAGLANTI_YONET']) {
  return render(<ProjectAccessProvider value={{ roles: ['GELISTIRICI'], permissions }}><MemoryRouter initialEntries={['/projects/project/environments']}><Routes><Route path="/projects/:projectUuid/environments" element={<EnvironmentsPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)
}

describe('EnvironmentsPage', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    await i18n.changeLanguage('en')
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input).includes('/record-audit/')) return new Response('[]', { status: 200 })
      throw new Error(`Unexpected unmocked request: ${String(input)}`)
    })
    vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([environmentFixture({ uuid: 'test', code: 'TEST', name: 'Test', risk: 'URETIM', defaultEnvironment: true })])
    vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([logicalSchemaFixture({ uuid: 'orders', code: 'ORDERS', name: 'Orders' })])
    vi.spyOn(topologyApi, 'listPhysicalSchemas').mockResolvedValue([physicalSchemaFixture({ uuid: 'physical-source', connectionUuid: 'source', schemaName: 'UPSTREAM_SCHEMA' })])
    vi.spyOn(topologyApi, 'listConnections').mockResolvedValue([connectionFixture({ uuid: 'source', code: 'SOURCE', name: 'SOURCE' })])
    vi.spyOn(topologyApi, 'listBindings').mockResolvedValue([bindingFixture({ uuid: 'b', logicalSchemaUuid: 'orders', environmentUuid: 'test', physicalSchemaUuid: 'physical-source' })])
  })

  it('lists environments with their risk class, default marker and mapping state', async () => {
    open(['BAGLANTI_GORUNTULE'])
    expect(await screen.findByText('Test')).toBeInTheDocument()
    expect(screen.getAllByText('Production').length).toBeGreaterThan(0)
    expect(screen.getAllByText('All schemas mapped').length).toBeGreaterThan(0)
    expect(screen.getAllByText('SOURCE / UPSTREAM_SCHEMA').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Add environment' })).not.toBeInTheDocument()
  })

  it('creates an environment with its risk class and default flag', async () => {
    const create = vi.spyOn(topologyApi, 'createEnvironment').mockResolvedValue(environmentFixture({ uuid: 'prod', code: 'PROD', name: 'Prod' }))
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Add environment' }))
    fireEvent.change(screen.getByLabelText('Name *'), { target: { value: 'Prod' } })
    fireEvent.change(screen.getByLabelText('Code *'), { target: { value: 'prod' } })
    expect(screen.getByLabelText('Transfer memory budget (MiB)')).toHaveValue(1024)
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Risk Class *' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Production' }))
    fireEvent.click(screen.getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith('project', { code: 'PROD', name: 'Prod', description: null, risk: 'URETIM', defaultEnvironment: true, transferMemoryBudgetMiB: 1024 }))
  })

  it('does not send a stale budget when only environment metadata is edited', async () => {
    const update = vi.spyOn(topologyApi, 'updateEnvironment').mockResolvedValue(environmentFixture({ uuid: 'test' }))
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit: Test', hidden: true }))
    fireEvent.change(screen.getByLabelText('Name *'), { target: { value: 'Renamed' } })
    fireEvent.submit(screen.getByLabelText('Transfer memory budget (MiB)').closest('form')!)
    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0]?.[2]).not.toHaveProperty('transferMemoryBudgetMiB')
  })

  it('sends an explicitly changed environment budget', async () => {
    const update = vi.spyOn(topologyApi, 'updateEnvironment').mockResolvedValue(environmentFixture({ uuid: 'test' }))
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit: Test', hidden: true }))
    fireEvent.change(screen.getByLabelText('Transfer memory budget (MiB)'), { target: { value: '64' } })
    fireEvent.submit(screen.getByLabelText('Transfer memory budget (MiB)').closest('form')!)
    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0]?.[2]).toHaveProperty('transferMemoryBudgetMiB', 64)
  })
})
