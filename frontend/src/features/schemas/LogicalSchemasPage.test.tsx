import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectAccessProvider } from '../../core/auth/ProjectAccessContext'
import i18n from '../../core/i18n'
import { topologyApi } from '../topology/api'
import { LogicalSchemasPage } from './LogicalSchemasPage'

describe('LogicalSchemasPage', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    await i18n.changeLanguage('en')
    vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([])
    vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([{ uuid: 'environment', code: 'TEST', name: 'Test', status: 'AKTIF', policyVersion: 1, version: 1 }])
    vi.spyOn(topologyApi, 'listPhysicalSchemas').mockResolvedValue([
      { uuid: 'physical-sky', connectionUuid: 'connection-sky', code: 'TTBP', name: 'TTBP', schemaReference: 'TTBP', status: 'AKTIF', version: 1 },
      { uuid: 'physical-gpu', connectionUuid: 'connection-gpu', code: 'INNOVA_ODI', name: 'INNOVA_ODI', schemaReference: 'INNOVA_ODI', status: 'AKTIF', version: 1 },
    ])
    vi.spyOn(topologyApi, 'listConnections').mockResolvedValue([
      { uuid: 'connection-sky', code: 'SKY', name: 'SKY', databaseType: 'ORACLE', status: 'AKTIF', version: 1 },
      { uuid: 'connection-gpu', code: 'GPU', name: 'GPU', databaseType: 'ORACLE', status: 'AKTIF', version: 1 },
    ])
  })

  it('creates a logical schema with environment and physical schema, without a revision choice', async () => {
    const create = vi.spyOn(topologyApi, 'createLogicalSchema').mockResolvedValue({ uuid: 'logical', code: 'LS_SKY', name: 'Sky logical', status: 'AKTIF', version: 1 })
    render(<ProjectAccessProvider value={{ roles: ['GELISTIRICI'], permissions: ['BAGLANTI_YONET'] }}><MemoryRouter initialEntries={['/projects/project/logical-schemas']}><Routes><Route path="/projects/:projectUuid/logical-schemas" element={<LogicalSchemasPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)

    fireEvent.click(await screen.findByRole('button', { name: 'Add logical schema' }))
    expect(screen.queryByLabelText(/revision/i)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Sky logical' } })
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'LS_SKY' } })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Physical schema' }))
    expect(await screen.findByRole('option', { name: 'GPU / INNOVA_ODI' })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('option', { name: 'SKY / TTBP' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create and Map' }))

    await waitFor(() => expect(create).toHaveBeenCalledWith('project', {
      code: 'LS_SKY',
      name: 'Sky logical',
      description: undefined,
      environmentUuid: 'environment',
      physicalSchemaUuid: 'physical-sky',
    }))
  })
})
