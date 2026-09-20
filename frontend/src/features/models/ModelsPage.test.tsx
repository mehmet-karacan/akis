import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi, type Model } from '../topology/api'
import { definitionsApi } from '../definitions/api'
import { ModelsPage } from './ModelsPage'

const access = vi.hoisted(() => ({ write: true }))
vi.mock('../../core/auth/ProjectAccessContext', () => ({ useProjectAccess: () => ({ can: (permission: string) => permission === 'KATALOG_KESFET' && access.write }) }))
vi.mock('../../core/ui/useRecordAudit', () => ({ useRecordAudit: () => ({ records: {}, state: 'ready' }) }))
const model: Model = { uuid: 'model', logicalSchemaUuid: 'schema', name: 'Orders', code: 'ORDERS', technologyCode: 'ORACLE', reverseMode: 'CUSTOM_RKM', rkmDefinitionUuid: 'rkm', reverseOptions: { INCLUDE_VIEWS: true, PREFIX: 'STG' }, status: 'AKTIF', version: 4 }
function open() {
  return render(<MemoryRouter initialEntries={['/projects/p/models?edit=model']}><Routes><Route path="/projects/:projectUuid/models" element={<ModelsPage />} /></Routes></MemoryRouter>)
}
beforeEach(async () => {
  vi.restoreAllMocks(); access.write = true; await i18n.changeLanguage('en')
  vi.spyOn(topologyApi, 'listModels').mockResolvedValue([model])
  vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([{ uuid: 'schema', name: 'Schema', code: 'S', status: 'AKTIF' }])
  vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([])
  vi.spyOn(definitionsApi, 'listDefinitions').mockResolvedValue([])
  vi.spyOn(topologyApi, 'updateModel').mockResolvedValue({ ...model, version: 5 })
})
it('preserves reverse options when only the model name changes', async () => {
  open()
  const name = await screen.findByLabelText('Model name')
  fireEvent.change(name, { target: { value: 'Renamed Orders' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(topologyApi.updateModel).toHaveBeenCalledWith('p', 'model', expect.objectContaining({
    name: 'Renamed Orders', expectedVersion: 4, reverseMode: 'CUSTOM_RKM', rkmDefinitionUuid: 'rkm', reverseOptions: model.reverseOptions,
  })))
})
it('does not allow edits without catalog write permission', async () => {
  access.write = false
  open()
  expect(await screen.findByLabelText('Model name')).toBeDisabled()
  expect(screen.getByRole('dialog')).toHaveTextContent('View Model')
  expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
  expect(topologyApi.updateModel).not.toHaveBeenCalled()
})
