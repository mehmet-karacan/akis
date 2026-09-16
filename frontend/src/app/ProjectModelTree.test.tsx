import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { topologyApi } from '../features/topology/api'
import { MODEL_OBJECT_DRAG_TYPE } from '../features/models/modelObjectDrag'
import { ProjectModelTree } from './ProjectModelTree'

beforeEach(async () => { vi.restoreAllMocks(); vi.spyOn(topologyApi, 'listSubmodels').mockResolvedValue([]); vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([{ uuid: 'l1', name: 'Source Schema', code: 'SOURCE', status: 'AKTIF', version: 1 }]); await i18n.changeLanguage('en') })
it('loads models only when expanded and navigates to the selected data object', async () => {
  const models = vi.spyOn(topologyApi, 'listModels').mockResolvedValue([{ uuid: 'm1', name: 'Source Model', code: 'SOURCE', logicalSchemaUuid: 'l1', status: 'AKTIF', version: 1 }])
  vi.spyOn(topologyApi, 'listDataObjects').mockResolvedValue([{ uuid: 'o1', modelUuid: 'm1', code: 'TABLE', objectReference: 'TTBP.HAKEDIS_TIPI', name: 'Hakedis', type: 'TABLE', status: 'AKTIF', version: 1 }])
  const navigate = vi.fn()
  render(<ul><ProjectModelTree projectUuid="p1" onNavigate={navigate} /></ul>)
  expect(models).not.toHaveBeenCalled()
  fireEvent.click(screen.getAllByRole('button', { name: 'Models' })[0]!)
  fireEvent.click((await screen.findByText('Source Model')).closest('button')!)
  expect(await screen.findByText('Source Schema')).toBeInTheDocument()
  const setData = vi.fn()
  fireEvent.dragStart(await screen.findByText('Hakedis'), { dataTransfer: { effectAllowed: 'none', setData } })
  expect(setData).toHaveBeenCalledWith(MODEL_OBJECT_DRAG_TYPE, expect.stringContaining('"objectUuid":"o1"'))
  fireEvent.click(await screen.findByText('Hakedis'))
  expect(navigate).toHaveBeenCalledWith('/project/models/m1?object=o1')
})
it('offers a retry after a failed catalog load', async () => {
  vi.spyOn(topologyApi, 'listModels').mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
  render(<ul><ProjectModelTree projectUuid="p1" onNavigate={vi.fn()} /></ul>)
  fireEvent.click(screen.getAllByRole('button', { name: 'Models' })[0]!)
  fireEvent.click(await screen.findByRole('button', { name: /Try Again|Retry/i }))
  await waitFor(() => expect(topologyApi.listModels).toHaveBeenCalledTimes(2))
})
