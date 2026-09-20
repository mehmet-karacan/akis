import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { topologyApi, type DataObject, type Submodel } from '../topology/api'
import { DataObjectFolderEditor } from './DataObjectFolderEditor'

vi.mock('../../core/api/networkFeedback', () => ({ notifyFeedback: vi.fn() }))
const object: DataObject = { uuid: 'object', modelUuid: 'model', code: 'ITEMS', name: 'Items', objectReference: 'APP.ITEMS', type: 'VIEW', status: 'AKTIF', version: 7 }
const folders: Submodel[] = [
  { uuid: 'parent', modelUuid: 'model', name: 'Business', code: 'B', version: 1 },
  { uuid: 'child', modelUuid: 'model', parentUuid: 'parent', name: 'Sales', code: 'S', version: 1 },
  { uuid: 'foreign', modelUuid: 'another', name: 'Foreign Folder', code: 'F', version: 1 },
]
beforeEach(async () => { vi.restoreAllMocks(); vi.clearAllMocks(); await i18n.changeLanguage('en') })
async function choose(label: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Data Store Folder' }))
  fireEvent.click(await screen.findByText(label))
}
it('shows full folder paths, excludes other models and preserves identity on save', async () => {
  const moved = { ...object, submodelUuid: 'child', version: 8 }
  vi.spyOn(topologyApi, 'moveDataObject').mockResolvedValue(moved)
  const onMoved = vi.fn()
  render(<DataObjectFolderEditor projectUuid="p" object={object} folders={folders} onMoved={onMoved} />)
  expect(screen.getByRole('button', { name: 'Update Folder' })).toBeDisabled()
  await choose('Business / Sales')
  expect(screen.queryByText('Foreign Folder')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Update Folder' }))
  await waitFor(() => expect(onMoved).toHaveBeenCalledWith(moved))
  expect(topologyApi.moveDataObject).toHaveBeenCalledWith('p', 'model', 'object', { submodelUuid: 'child', expectedVersion: 7 })
  expect(notifyFeedback).toHaveBeenCalledWith('Data store folder updated.', 'success')
})
it('moves a nested object to root using explicit null', async () => {
  vi.spyOn(topologyApi, 'moveDataObject').mockResolvedValue({ ...object, version: 8 })
  render(<DataObjectFolderEditor projectUuid="p" object={{ ...object, submodelUuid: 'child' }} folders={folders} onMoved={vi.fn()} />)
  await choose('Model Root')
  fireEvent.click(screen.getByRole('button', { name: 'Update Folder' }))
  await waitFor(() => expect(topologyApi.moveDataObject).toHaveBeenCalledWith('p', 'model', 'object', { submodelUuid: null, expectedVersion: 7 }))
})
it('retains selection on conflict and does not claim success or retry automatically', async () => {
  vi.spyOn(topologyApi, 'moveDataObject').mockRejectedValue(new Error('VERSION_CONFLICT'))
  const onMoved = vi.fn()
  render(<DataObjectFolderEditor projectUuid="p" object={object} folders={folders} onMoved={onMoved} />)
  await choose('Business / Sales')
  fireEvent.click(screen.getByRole('button', { name: 'Update Folder' }))
  await waitFor(() => expect(notifyFeedback).toHaveBeenCalledWith('VERSION_CONFLICT', 'error'))
  expect(onMoved).not.toHaveBeenCalled()
  expect(topologyApi.moveDataObject).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('combobox', { name: 'Data Store Folder' }).closest('.ant-select-content')).toHaveTextContent('Business / Sales')
})
it('does not navigate after leaving the editor and submits only one pending request', async () => {
  let done!: (value: DataObject) => void
  vi.spyOn(topologyApi, 'moveDataObject').mockImplementation(() => new Promise(resolve => { done = resolve }))
  const onMoved = vi.fn()
  const { unmount } = render(<DataObjectFolderEditor projectUuid="p" object={object} folders={folders} onMoved={onMoved} />)
  await choose('Business / Sales')
  const save = screen.getByRole('button', { name: 'Update Folder' })
  fireEvent.click(save); fireEvent.click(save)
  expect(topologyApi.moveDataObject).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('combobox', { name: 'Data Store Folder' })).toBeDisabled()
  unmount()
  await act(async () => done({ ...object, submodelUuid: 'child', version: 8 }))
  expect(onMoved).not.toHaveBeenCalled()
  expect(notifyFeedback).not.toHaveBeenCalled()
})
