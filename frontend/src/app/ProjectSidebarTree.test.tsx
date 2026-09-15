import { fireEvent, render as testingRender, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import type { Definition, Folder } from '../features/definitions/types'
import { ProjectAccessProvider } from '../core/auth/ProjectAccessContext'
import { ConfigProvider } from 'antd'

const access = { roles: ['GELISTIRICI'], permissions: ['TANIM_DUZENLE', 'TANIM_DOGRULA'] }
const render = (element: React.ReactElement) => testingRender(<ConfigProvider theme={{ token: { motion: false } }}><ProjectAccessProvider value={access}>{element}</ProjectAccessProvider></ConfigProvider>)

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
    const object = screen.getByRole('button', { name: 'Load Daily' })
    expect(object.closest('.ant-tree-node-content-wrapper')).toHaveClass('ant-tree-node-selected')
    fireEvent.click(object)
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
  })

  it('opens the same object action menu from right click and the three-dot button', async () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /Procedures\s*1/ }))
    const object = await screen.findByRole('button', { name: 'Load Daily' })
    fireEvent.contextMenu(object.closest('.akis-tree-title')!)
    expect(await screen.findByRole('menuitem', { name: 'Create Scenario' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Run' }))
    expect(navigate).toHaveBeenCalledWith('/project/operations?definition=procedure-1&start=1')

    fireEvent.click(await screen.findByRole('button', { name: /Actions for Load Daily/i }))
    expect(await screen.findByRole('menuitem', { name: 'Open' })).toBeInTheDocument()
  })

  it('moves through context menu actions with arrow keys', async () => {
    const rectangles = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 32))
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Procedures\s*1/ }))
    fireEvent.click(await screen.findByRole('button', { name: /Actions for Load Daily/i }))
    const open = await screen.findByRole('menuitem', { name: 'Open' })
    open.focus()
    fireEvent.keyDown(open, { key: 'ArrowDown', keyCode: 40, which: 40 })
    await vi.waitFor(() => expect(screen.getByRole('menuitem', { name: 'Create Scenario' })).toHaveFocus())
    fireEvent.keyDown(document.activeElement!, { key: 'End', keyCode: 35, which: 35 })
    await vi.waitFor(() => expect(screen.getByRole('menuitem', { name: 'Run' })).toHaveFocus())
    rectangles.mockRestore()
  })

  it('creates a subfolder from the selected folder context', async () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.contextMenu(screen.getByRole('button', { name: /Actions for Loads/i }).parentElement!)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Create Subfolder' }))

    expect(navigate).toHaveBeenCalledWith('/project/objects?createFolder=folder-1')
  })

  it('shows the fixed project groups and opens models directly', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition, variable]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByText('Flows').closest('button')).toBeInTheDocument()
    expect(screen.getByText('Models').closest('button')).toBeInTheDocument()
    expect(screen.getByText('Shared Components').closest('button')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Variable').closest('button')!)
    expect(screen.getByText('Run Date')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Open: Models'))
    expect(navigate).toHaveBeenCalledWith('/project/models')
  })

  it('offers component creation from the visible add button and right click', async () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[]} definitions={[]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /Add component/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Add Variable' }))
    expect(navigate).toHaveBeenCalledWith('/project/objects?createType=VARIABLE')
    await waitFor(() => expect(screen.queryByRole('menuitem', { name: 'Add Variable' })).not.toBeInTheDocument())

    fireEvent.contextMenu(screen.getByText('Sequence generator').closest('.sidebar-folder-action-row')!)
    expect(await screen.findByRole('menuitem', { name: 'Add Sequence generator' })).toBeInTheDocument()
  })

  it('keeps reusable mappings out of the project navigation', () => {
    const reusable: Definition = { ...definition, uuid: 'reusable-1', type: 'REUSABLE_MAPPING', name: 'Legacy Reusable' }
    render(<ProjectSidebarTree projectUuid="project-1" folders={[]} definitions={[reusable]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)

    expect(screen.queryByText('Legacy Reusable')).not.toBeInTheDocument()
  })
})
