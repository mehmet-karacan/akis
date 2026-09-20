import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { topologyApi } from '../features/topology/api'
import { MODEL_OBJECT_DRAG_TYPE } from '../features/models/modelObjectDrag'
import { ProjectModelTree } from './ProjectModelTree'

beforeEach(async () => { vi.restoreAllMocks(); vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([]); vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([{ uuid: 'l1', name: 'Source Schema', code: 'SOURCE', status: 'AKTIF' }]); await i18n.changeLanguage('en') })
const expand = (name: string) => fireEvent.click(screen.getByText(name).closest('[role="treeitem"]')!.querySelector('.ant-tree-switcher')!)
it('loads on expansion, single-selects and opens objects on double-click or Enter without edit buttons', async () => {
  const models = vi.spyOn(topologyApi, 'listModels').mockResolvedValue([{ uuid: 'm1', name: 'Source Model', code: 'SOURCE', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([{ uuid: 'o1', modelUuid: 'm1', code: 'TABLE', objectReference: 'TTBP.HAKEDIS_TIPI', name: 'Hakedis', type: 'TABLE', status: 'AKTIF', version: 1 }])
  const navigate = vi.fn()
  render(<ProjectModelTree projectUuid="p1" onNavigate={navigate} />)
  expect(models).not.toHaveBeenCalled()
  fireEvent.doubleClick(screen.getByText('Models'))
  expect(navigate).not.toHaveBeenCalled()
  expand('Models')
  await screen.findByText('Source Model')
  expand('Source Model')
  const setData = vi.fn()
  fireEvent.dragStart(await screen.findByText('Hakedis'), { dataTransfer: { effectAllowed: 'none', setData } })
  expect(setData).toHaveBeenCalledWith(MODEL_OBJECT_DRAG_TYPE, expect.stringContaining('"objectUuid":"o1"'))
  fireEvent.click(await screen.findByText('Hakedis'))
  expect(navigate).not.toHaveBeenCalledWith('/project/models/m1?object=o1')
  expect(screen.queryByRole('button', { name: 'Edit: Hakedis' })).not.toBeInTheDocument()
  fireEvent.doubleClick(screen.getByText('Hakedis'))
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?object=o1')
  navigate.mockClear()
  fireEvent.keyDown(screen.getByText('Hakedis').closest('[tabindex="0"]')!, { key: 'Enter' })
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?object=o1')
  act(() => window.dispatchEvent(new Event('akis:models-changed')))
  expand('Models')
  await waitFor(() => expect(models).toHaveBeenCalledTimes(2))
})
it('offers a retry after a failed catalog load', async () => {
  vi.spyOn(topologyApi, 'listModels').mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
  render(<ProjectModelTree projectUuid="p1" onNavigate={vi.fn()} />)
  expand('Models')
  fireEvent.click(await screen.findByRole('button', { name: /Try Again|Retry/i }))
  await waitFor(() => expect(topologyApi.listModels).toHaveBeenCalledTimes(2))
})
it('acceptance item 9: model/folder/datastore open with double-click or Enter, Models root stays a grouping, and rows have no edit button', async () => {
  vi.spyOn(topologyApi, 'listModels').mockResolvedValue([{ uuid: 'm1', name: 'Source Model', code: 'SOURCE', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }])
  vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([{ uuid: 'f1', modelUuid: 'm1', parentUuid: null, name: 'Sales', code: 'SALES', version: 1 }])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([
    { uuid: 'o1', submodelUuid: 'f1', modelUuid: 'm1', code: 'T', objectReference: 'APP.T', name: 'Sales Table', type: 'TABLE', status: 'AKTIF', version: 1 },
    { uuid: 'v1', modelUuid: 'm1', code: 'V', objectReference: 'APP.V', name: 'Global View', type: 'VIEW', status: 'AKTIF', version: 1 },
  ])
  const navigate = vi.fn()
  render(<ProjectModelTree projectUuid="p1" onNavigate={navigate} />)

  const modelsRoot = screen.getByText('Models')
  fireEvent.doubleClick(modelsRoot)
  fireEvent.keyDown(modelsRoot.closest('[tabindex="0"]')!, { key: 'Enter' })
  expect(navigate).not.toHaveBeenCalled()

  expand('Models')
  await screen.findByText('Source Model')
  fireEvent.doubleClick(screen.getByText('Source Model'))
  expect(navigate).toHaveBeenCalledWith('/project/models/m1')
  navigate.mockClear()

  expand('Source Model')
  const folder = await screen.findByText('Sales')
  fireEvent.doubleClick(folder)
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?folder=f1')
  navigate.mockClear()

  const salesNode = folder.closest('[role="treeitem"]')!
  if (salesNode.getAttribute('aria-expanded') !== 'true') {
    fireEvent.click(salesNode.querySelector('.ant-tree-switcher')!)
  }
  const table = await screen.findByText('Sales Table')
  expect(screen.queryByLabelText(/Edit: Sales Table/i)).not.toBeInTheDocument()
  fireEvent.click(table)
  expect(navigate).not.toHaveBeenCalled()
  fireEvent.doubleClick(screen.getByText('Sales Table'))
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?object=o1')
  navigate.mockClear()
  fireEvent.keyDown(screen.getByText('Sales Table').closest('[tabindex="0"]')!, { key: 'Enter' })
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?object=o1')

  const view = screen.getByText('Global View')
  fireEvent.doubleClick(view)
  expect(navigate).toHaveBeenLastCalledWith('/project/models/m1?object=v1')
})

it('opens nested folders and views in context, with Models as a grouping only', async () => {
  vi.spyOn(topologyApi, 'listModels').mockResolvedValue([{ uuid: 'm1', name: 'Source Model', code: 'SOURCE', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }])
  vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([{ uuid: 'f1', modelUuid: 'm1', parentUuid: null, name: 'Sales', code: 'SALES', version: 1 }])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([{ uuid: 'v1', submodelUuid: 'f1', modelUuid: 'm1', code: 'V', objectReference: 'APP.V', name: 'Sales View', type: 'VIEW', status: 'AKTIF', version: 1 }])
  const navigate = vi.fn()
  render(<ProjectModelTree projectUuid="p1" onNavigate={navigate} />)
  expand('Models'); await screen.findByText('Source Model'); expand('Source Model')
  fireEvent.doubleClick(await screen.findByText('Sales'))
  expect(navigate).toHaveBeenLastCalledWith('/project/models/m1?folder=f1')
  expand('Sales'); fireEvent.doubleClick(await screen.findByText('Sales View'))
  expect(navigate).toHaveBeenLastCalledWith('/project/models/m1?object=v1')
})

it('does not carry another project catalog or a delayed response into the active tree', async () => {
  let complete!: (value: Awaited<ReturnType<typeof topologyApi.listModels>>) => void
  vi.spyOn(topologyApi, 'listModels').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
    .mockResolvedValueOnce([{ uuid: 'm2', name: 'Current Model', code: 'CURRENT', logicalSchemaUuid: 'l2', status: 'AKTIF', version: 1 }])
  const rendered = render(<ProjectModelTree projectUuid="first" onNavigate={vi.fn()} />)
  expand('Models')
  await waitFor(() => expect(topologyApi.listModels).toHaveBeenCalledWith('first'))
  rendered.rerender(<ProjectModelTree projectUuid="second" onNavigate={vi.fn()} />)
  expand('Models')
  await screen.findByText('Current Model')
  await act(async () => complete([{ uuid: 'm1', name: 'Old Model', code: 'OLD', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }]))
  expect(screen.queryByText('Old Model')).not.toBeInTheDocument()
  expect(screen.getByText('Current Model')).toBeInTheDocument()
})

it('ignores model responses started before an explicit catalog refresh', async () => {
  let complete!: (value: Awaited<ReturnType<typeof topologyApi.listModels>>) => void
  vi.spyOn(topologyApi, 'listModels').mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
    .mockResolvedValueOnce([{ uuid: 'fresh', name: 'Fresh Model', code: 'FRESH', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }])
  render(<ProjectModelTree projectUuid="p1" onNavigate={vi.fn()} />)
  expand('Models')
  act(() => window.dispatchEvent(new Event('akis:models-changed')))
  await act(async () => complete([{ uuid: 'old', name: 'Old Model', code: 'OLD', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }]))
  expect(screen.queryByText('Old Model')).not.toBeInTheDocument()
  expand('Models')
  expect(await screen.findByText('Fresh Model')).toBeInTheDocument()
})
