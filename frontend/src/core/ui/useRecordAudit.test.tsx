import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { apiRequest } from '../api/client'
import { recordChangedEvent } from '../api/recordChanges'
import { useRecordAudit, type RecordAudit } from './useRecordAudit'

const context = vi.hoisted(() => ({ project: 'p' }))
vi.mock('../../features/projects/CurrentProjectContext', () => ({ useCurrentProjectUuid: () => context.project }))
vi.mock('../api/client', () => ({ apiRequest: vi.fn() }))
const record = (name: string): RecordAudit => ({ uuid: 'o', createdBy: 'Creator', createdAt: null, updatedBy: name, updatedAt: null })
function changed(projectUuid: string, kind: string) {
  act(() => window.dispatchEvent(new CustomEvent(recordChangedEvent, { detail: { projectUuid, kind } })))
}
beforeEach(() => { vi.resetAllMocks(); context.project = 'p' })
it('reloads the matching audit kind after a successful write without page refresh', async () => {
  vi.mocked(apiRequest).mockResolvedValueOnce([record('First')]).mockResolvedValueOnce([record('Second')])
  const { result } = renderHook(() => useRecordAudit('data-objects'))
  await waitFor(() => expect(result.current.records.o?.updatedBy).toBe('First'))
  changed('other', 'data-objects'); changed('p', 'connections')
  expect(apiRequest).toHaveBeenCalledTimes(1)
  changed('p', 'data-objects')
  await waitFor(() => expect(result.current.records.o?.updatedBy).toBe('Second'))
  expect(apiRequest).toHaveBeenCalledTimes(2)
})
it('ignores a stale audit response after a mutation starts a newer read', async () => {
  let done!: (value: RecordAudit[]) => void
  vi.mocked(apiRequest).mockImplementationOnce(() => new Promise(resolve => { done = resolve })).mockResolvedValueOnce([record('Latest')])
  const { result } = renderHook(() => useRecordAudit('models'))
  changed('p', 'models')
  await waitFor(() => expect(result.current.records.o?.updatedBy).toBe('Latest'))
  await act(async () => done([record('Stale')]))
  expect(result.current.records.o?.updatedBy).toBe('Latest')
})
it('clears prior data on context failure and removes its event listener on unmount', async () => {
  vi.mocked(apiRequest).mockResolvedValueOnce([record('First')]).mockRejectedValueOnce(new Error('unavailable'))
  const { result, rerender, unmount } = renderHook(() => useRecordAudit('models'))
  await waitFor(() => expect(result.current.state).toBe('ready'))
  context.project = 'next project'; rerender()
  await waitFor(() => expect(result.current.state).toBe('error'))
  expect(result.current.records).toEqual({})
  expect(apiRequest).toHaveBeenLastCalledWith('/api/v1/projects/next%20project/record-audit/models')
  unmount(); changed('next project', 'models')
  expect(apiRequest).toHaveBeenCalledTimes(2)
})
