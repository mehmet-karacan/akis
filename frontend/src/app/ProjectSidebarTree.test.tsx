import { fireEvent, render as testingRender, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import type { Definition, Folder } from '../features/definitions/types'
import { ProjectAccessProvider } from '../core/auth/ProjectAccessContext'

const access = { roles: ['GELISTIRICI'], permissions: ['TANIM_DUZENLE', 'TANIM_DOGRULA'] }
const render = (element: React.ReactElement) => testingRender(<ProjectAccessProvider value={access}>{element}</ProjectAccessProvider>)

const folder: Folder = {
  uuid: 'folder-1', parentUuid: null, code: 'LOADS',
  status: 'AKTIF', name: 'Loads', description: null, version: 1,
}
const definition: Definition = {
  uuid: 'procedure-1', folderUuid: folder.uuid, type: 'PROCEDURE', code: 'LOAD_DAILY',
  status: 'TASLAK', name: 'Load Daily', description: null, version: 1,
}
const variable: Definition = {
  uuid: 'variable-1', folderUuid: null, type: 'VARIABLE', code: 'RUN_DATE',
  status: 'TASLAK', name: 'Run Date', description: null, version: 1,
}

describe('persistent project sidebar tree', () => {
  beforeEach(async () => { await i18n.changeLanguage('en') })

  it('keeps draft project objects visible and opens the selected editor directly', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid="procedure-1" loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByRole('region', { name: 'PROJECT OBJECTS' })).toBeInTheDocument()
    const object = screen.getByTitle('Load Daily · Procedure')
    expect(object.parentElement).toHaveClass('is-selected')
    fireEvent.click(object)
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
  })

  it('opens the same object action menu from right click and the three-dot button', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /Procedures1/ }))
    const object = screen.getByTitle('Load Daily · Procedure')
    fireEvent.contextMenu(object.parentElement!)
    expect(screen.getByRole('menuitem', { name: 'Create Scenario' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Run' }))
    expect(navigate).toHaveBeenCalledWith('/project/operations?definition=procedure-1&start=1')

    fireEvent.click(screen.getByRole('button', { name: 'Actions for Load Daily' }))
    expect(screen.getByRole('menuitem', { name: 'Open' })).toBeInTheDocument()
  })

  it('moves through context menu actions with arrow keys', async () => {
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Procedures1/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Actions for Load Daily' }))
    const open = await screen.findByRole('menuitem', { name: 'Open' })
    await vi.waitFor(() => expect(open).toHaveFocus())
    fireEvent.keyDown(open.closest('[role="menu"]')!, { key: 'ArrowDown' })
    expect(screen.getByRole('menuitem', { name: 'Create Scenario' })).toHaveFocus()
    fireEvent.keyDown(open.closest('[role="menu"]')!, { key: 'End' })
    expect(screen.getByRole('menuitem', { name: 'Run' })).toHaveFocus()
  })

  it('creates a subfolder from the selected folder context', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.contextMenu(screen.getByRole('button', { name: 'Actions for Loads' }).parentElement!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Create Subfolder' }))

    expect(navigate).toHaveBeenCalledWith('/project/objects?createFolder=folder-1')
  })

  it('shows the fixed project groups and opens models directly', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition, variable]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByRole('button', { name: /Flows/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Models' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Shared Components/ })).toBeInTheDocument()
    fireEvent.click(screen.getByText('Variable').closest('button')!)
    expect(screen.getByText('Run Date')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Models' }))
    expect(navigate).toHaveBeenCalledWith('/project/models')
  })

  it('offers component creation from the visible add button and right click', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[]} definitions={[]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Add component' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add Variable' }))
    expect(navigate).toHaveBeenCalledWith('/project/objects?createType=VARIABLE')

    fireEvent.contextMenu(screen.getByText('Sequence generator').closest('.sidebar-folder-action-row')!)
    expect(screen.getByRole('menuitem', { name: 'Add Sequence generator' })).toBeInTheDocument()
  })

  it('keeps reusable mappings out of the project navigation', () => {
    const reusable: Definition = { ...definition, uuid: 'reusable-1', type: 'REUSABLE_MAPPING', name: 'Legacy Reusable' }
    render(<ProjectSidebarTree projectUuid="project-1" folders={[]} definitions={[reusable]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)

    expect(screen.queryByText('Legacy Reusable')).not.toBeInTheDocument()
  })
})
