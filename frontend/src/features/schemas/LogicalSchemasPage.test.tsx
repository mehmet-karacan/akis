import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectAccessProvider } from '../../core/auth/ProjectAccessContext'
import i18n from '../../core/i18n'
import { topologyApi } from '../topology/api'
import { LogicalSchemasPage } from './LogicalSchemasPage'
import { bindingFixture, connectionFixture, environmentFixture, logicalSchemaFixture, physicalSchemaFixture } from '../topology/testFixtures'

function open(permissions: string[] = ['BAGLANTI_YONET']) {
  return render(<ProjectAccessProvider value={{ roles: ['GELISTIRICI'], permissions }}><MemoryRouter initialEntries={['/projects/project/logical-schemas']}><Routes><Route path="/projects/:projectUuid/logical-schemas" element={<LogicalSchemasPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)
}

describe('LogicalSchemasPage', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    await i18n.changeLanguage('en')
    vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([])
    vi.spyOn(topologyApi, 'listBindings').mockResolvedValue([])
    vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([environmentFixture({ uuid: 'environment', code: 'TEST', name: 'Test' })])
    vi.spyOn(topologyApi, 'listPhysicalSchemas').mockResolvedValue([
      physicalSchemaFixture({ uuid: 'physical-source', connectionUuid: 'connection-source', schemaName: 'UPSTREAM_SCHEMA' }),
      physicalSchemaFixture({ uuid: 'physical-pg', connectionUuid: 'connection-pg', schemaName: 'public', databaseType: 'POSTGRESQL' }),
    ])
    vi.spyOn(topologyApi, 'listConnections').mockResolvedValue([
      connectionFixture({ uuid: 'connection-source', code: 'SOURCE', name: 'SOURCE' }),
      connectionFixture({ uuid: 'connection-pg', code: 'PG', name: 'PG', databaseType: 'POSTGRESQL' }),
    ])
  })

  it('creates a logical schema for one provider and maps only same-provider physical schemas', async () => {
    const create = vi.spyOn(topologyApi, 'createLogicalSchema').mockResolvedValue(logicalSchemaFixture({ uuid: 'logical', code: 'LS_SOURCE', name: 'Source logical' }))
    open()

    fireEvent.click(await screen.findByRole('button', { name: 'Add logical schema' }))
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Provider *' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Oracle' }))
    fireEvent.change(screen.getByLabelText('Name *'), { target: { value: 'Source logical' } })
    fireEvent.change(screen.getByLabelText('Code *'), { target: { value: 'LS_SOURCE' } })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Environment' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Test · TEST' }))
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Physical schema' }))
    expect(await screen.findByRole('option', { name: 'SOURCE / UPSTREAM_SCHEMA' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'PG / public' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('option', { name: 'SOURCE / UPSTREAM_SCHEMA' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create and Map' }))

    await waitFor(() => expect(create).toHaveBeenCalledWith('project', {
      code: 'LS_SOURCE', name: 'Source logical', description: undefined, databaseType: 'ORACLE',
      environmentUuid: 'environment', physicalSchemaUuid: 'physical-source',
    }))
  })

  it('shows the mapping state of every schema in the catalog', async () => {
    vi.mocked(topologyApi.listLogicalSchemas).mockResolvedValue([logicalSchemaFixture({ uuid: 'orders', code: 'ORDERS', name: 'Orders' })])
    vi.mocked(topologyApi.listBindings).mockResolvedValue([bindingFixture({ uuid: 'b', logicalSchemaUuid: 'orders', environmentUuid: 'environment', physicalSchemaUuid: 'physical-source' })])
    open(['BAGLANTI_GORUNTULE'])

    expect(await screen.findByText('Orders')).toBeInTheDocument()
    expect(screen.getAllByText('All environments mapped').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Add logical schema' })).not.toBeInTheDocument()
  })
})
