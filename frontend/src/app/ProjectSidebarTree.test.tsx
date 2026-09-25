import { fireEvent, render as testingRender, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import type { Definition, Folder } from '../features/definitions/types'
import { ProjectAccessProvider } from '../core/auth/ProjectAccessContext'
import { ConfigProvider } from 'antd'
import { operationsApi } from '../features/operations/api'
import { executionApi } from '../features/execution/api'

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

  it('selects objects on one click and opens with double-click or Enter without edit buttons', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid="procedure-1" loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByRole('region', { name: 'PROJECT OBJECTS' })).toBeInTheDocument()
    const object = screen.getByText('Load Daily', { exact: true })
    expect(screen.queryByRole('button', { name: 'Load Daily' })).not.toBeInTheDocument()
    expect(object.closest('.ant-tree-node-content-wrapper')).toHaveClass('ant-tree-node-selected')
    expect(screen.queryByLabelText('Edit: Load Daily')).not.toBeInTheDocument()
    fireEvent.click(object)
    expect(navigate).not.toHaveBeenCalled()
    fireEvent.doubleClick(object)
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
    navigate.mockClear()
    fireEvent.keyDown(object.closest('[tabindex="0"]')!, { key: 'Enter' })
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
  })

  it('opens the same object action menu from right click and the three-dot button', async () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Procedures' }))
    const object = await screen.findByText('Load Daily', { exact: true })
    fireEvent.contextMenu(object.closest('.akis-tree-title')!)
    expect(await screen.findByRole('menuitem', { name: 'Create Scenario' })).toBeInTheDocument()
    vi.spyOn(operationsApi, 'listPublications').mockResolvedValue([{ uuid: 'pub-1', definitionUuid: 'procedure-1', status: 'AKTIF', publicationNumber: 2, environmentCode: 'TEST' } as never])
    vi.spyOn(executionApi, 'startRun').mockResolvedValue({ runUuid: 'run-1' } as never)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Run' }))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/project/operations?run=run-1'))
    expect(executionApi.startRun).toHaveBeenCalledWith('project-1', 'pub-1', expect.any(String))

    fireEvent.click(await screen.findByRole('button', { name: /Actions for Load Daily/i }))
    expect(await screen.findByRole('menuitem', { name: 'Create Scenario' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Open' })).not.toBeInTheDocument()
  })

  it('moves through context menu actions with arrow keys', async () => {
    const rectangles = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 32))
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Procedures' }))
    fireEvent.click(await screen.findByRole('button', { name: /Actions for Load Daily/i }))
    const createScenario = await screen.findByRole('menuitem', { name: 'Create Scenario' })
    createScenario.focus()
    fireEvent.keyDown(createScenario, { key: 'ArrowDown', keyCode: 40, which: 40 })
    await vi.waitFor(() => expect(screen.getByRole('menuitem', { name: 'Run' })).toHaveFocus())
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

  it('shows fixed groups and treats the Models root as a grouping rather than a screen', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition, variable]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByText('Flows').closest('button')).toBeInTheDocument()
    expect(screen.getByText('Models').closest('[role="treeitem"]')).toBeInTheDocument()
    expect(screen.getByText('Shared Components').closest('button')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Variable').closest('button')!)
    expect(screen.getByText('Run Date')).toBeInTheDocument()

    fireEvent.doubleClick(screen.getByText('Models'))
    expect(navigate).not.toHaveBeenCalled()
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

  it('acceptance item 3: compact project-objects explorer opens editable child definitions with double-click or Enter, root screens stay non-openable, and rows have no edit button', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition, variable]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByRole('searchbox', { name: /search project objects/i })).toBeInTheDocument()
    expect(screen.getByText('Flows').closest('button')).toBeInTheDocument()
    expect(screen.getByText('Shared Components').closest('button')).toBeInTheDocument()

    fireEvent.doubleClick(screen.getByText('Flows'))
    fireEvent.doubleClick(screen.getByText('Shared Components'))
    fireEvent.keyDown(screen.getByText('Flows'), { key: 'Enter' })
    expect(navigate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByText('Procedures').closest('button')!)
    const object = screen.getByText('Load Daily', { exact: true })
    expect(screen.queryByLabelText(/Edit: Load Daily/i)).not.toBeInTheDocument()
    fireEvent.click(object)
    expect(navigate).not.toHaveBeenCalled()
    fireEvent.doubleClick(object)
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
    navigate.mockClear()
    fireEvent.keyDown(object.closest('[tabindex="0"]')!, { key: 'Enter' })
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
  })

  it('acceptance item 3: search filters project object rows while keeping root sections visible', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition, variable]} selectedUuid={null} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    const search = screen.getByRole('searchbox', { name: /search project objects/i })
    fireEvent.change(search, { target: { value: 'Run Date' } })
    expect(screen.getByText('Run Date')).toBeInTheDocument()
    expect(screen.queryByText('Load Daily')).not.toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'Load' } })
    expect(screen.getByText('Load Daily')).toBeInTheDocument()
    expect(screen.queryByText('Run Date')).not.toBeInTheDocument()
  })

  it('keeps reusable mappings out of the project navigation', () => {
    const reusable: Definition = { ...definition, uuid: 'reusable-1', type: 'REUSABLE_MAPPING', name: 'Legacy Reusable' }
    render(<ProjectSidebarTree projectUuid="project-1" folders={[]} definitions={[reusable]} selectedUuid={null} loading={false} failed={false} onNavigate={vi.fn()} onRetry={vi.fn()} />)

    expect(screen.queryByText('Legacy Reusable')).not.toBeInTheDocument()
  })
  it('reveals all ancestors of a deeply nested selected object and opens it with Enter', () => {
    const child: Folder = { ...folder, uuid: 'child', parentUuid: folder.uuid, name: 'Daily' }
    const leaf: Folder = { ...folder, uuid: 'leaf', parentUuid: child.uuid, name: 'Imports' }
    const nested: Definition = { ...definition, folderUuid: leaf.uuid }
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder, child, leaf]} definitions={[nested]} selectedUuid={nested.uuid} loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)
    const object = screen.getByText(nested.name, { exact: true })
    expect(object).toBeVisible()
    fireEvent.keyDown(object.closest('[tabindex="0"]')!, { key: 'Enter' })
    expect(navigate).toHaveBeenCalledWith('/project/objects/definitions/procedure-1')
  })
})
