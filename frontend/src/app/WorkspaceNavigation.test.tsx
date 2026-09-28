import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { resolveNavigationItem, resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'
import { topologyApi } from '../features/topology/api'

describe('WorkspaceNavigation', () => {
  beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })

  it('offers direct navigation across the project, execution and infrastructure areas', () => {
    render(<MemoryRouter initialEntries={['/project/objects']}><WorkspaceNavigation hasPendingChanges={false} onNavigate={() => undefined} /></MemoryRouter>)
    expect(screen.getAllByRole('menuitem').map((tab) => tab.textContent)).toEqual([
      'Overview', 'Flow Design', 'Models', 'Run History', 'Publications', 'Schedules',
      'Connections', 'Logical Schemas', 'Environments', 'Schema Metadata',
    ])
  })

  it('maps legacy routes into the canonical workspace', () => {
    expect(resolveWorkspace('/projects/p-1/topology')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/schema-bindings')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/runs')).toBe('operations')
    expect(resolveWorkspace('/projects/p-1/models')).toBe('development')
    expect(resolveWorkspace('/projects/p-1')).toBe('project')
  })

  it('highlights the exact destination and navigates directly to it', () => {
    const onNavigate = vi.fn()
    render(<MemoryRouter initialEntries={['/project/publications']}><WorkspaceNavigation hasPendingChanges={false} onNavigate={onNavigate} /></MemoryRouter>)
    expect(resolveNavigationItem('/project/publications')).toBe('publications')
    expect(resolveNavigationItem('/project/logical-schemas/abc')).toBe('logical-schemas')
    expect(resolveNavigationItem('/project/models/abc')).toBe('models')
    expect(screen.getByRole('menuitem', { name: 'Publications' })).toHaveClass('ant-menu-item-selected')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Schedules' }))
    expect(onNavigate).toHaveBeenCalledWith('/project/schedules')
  })

  it('shows models, nested folders and data stores as one navigable tree', async () => {
    vi.spyOn(topologyApi, 'listModels').mockResolvedValue([{ uuid: 'model', code: 'SALES', name: 'Sales Model', logicalSchemaUuid: 'schema', status: 'AKTIF', version: 1 }])
    const listFolders = vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([
      { uuid: 'folder', modelUuid: 'model', parentUuid: null, code: 'ORDERS', name: 'Orders Folder', version: 1 },
      { uuid: 'nested', modelUuid: 'model', parentUuid: 'folder', code: 'ARCHIVE', name: 'Archive Folder', version: 1 },
    ])
    const listObjects = vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([
      { uuid: 'table', modelUuid: 'model', submodelUuid: 'nested', code: 'ORDERS_2026', name: 'Orders 2026', objectReference: 'ORDERS_2026', type: 'TABLE', status: 'AKTIF', version: 1 },
    ])
    const onNavigate = vi.fn()
    render(<MemoryRouter initialEntries={['/project/models/SALES']}><WorkspaceNavigation projectUuid="p" hasPendingChanges={false} onNavigate={onNavigate} /></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Sales Model' })).toBeInTheDocument())
    expect(screen.getByRole('menuitem', { name: 'All Models' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Orders Folder' })).toBeInTheDocument())
    expect(listFolders).toHaveBeenCalledOnce()
    expect(listObjects).toHaveBeenCalledOnce()
    const modelNode = screen.getByRole('menuitem', { name: 'Sales Model' })
    expect(modelNode).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(modelNode)
    expect(modelNode).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(modelNode)
    expect(modelNode).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Open Model' }))
    expect(onNavigate).toHaveBeenCalledWith('/project/models/SALES')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Orders Folder' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Open Folder' }))
    expect(onNavigate).toHaveBeenCalledWith('/project/models/SALES?folder=ORDERS')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Archive Folder' }))
    fireEvent.click(screen.getAllByRole('menuitem', { name: 'Open Folder' })[1]!)
    expect(onNavigate).toHaveBeenCalledWith('/project/models/SALES?folder=ARCHIVE')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Orders 2026' }))
    expect(onNavigate).toHaveBeenCalledWith('/project/models/SALES?folder=ARCHIVE&object=ORDERS_2026')
  })
})
