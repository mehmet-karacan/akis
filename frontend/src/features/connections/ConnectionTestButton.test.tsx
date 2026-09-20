import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi } from '../topology/api'
import { ConnectionTestButton } from './ConnectionTestButton'
import { notifyFeedback } from '../../core/api/networkFeedback'

vi.mock('../projects/CurrentProjectContext', () => ({ useCurrentProjectUuid: () => 'project' }))
vi.mock('../../core/auth/ProjectAccessContext', () => ({ useProjectAccess: () => ({ can: () => true }) }))
vi.mock('../../core/api/networkFeedback', () => ({ notifyFeedback: vi.fn() }))
beforeEach(async () => { await i18n.changeLanguage('en'); vi.restoreAllMocks() })
it('tests a saved connection without opening its details', async () => {
  const test = vi.spyOn(topologyApi, 'testConnection').mockResolvedValue({ uuid: 'test', attemptNumber: 1, outcome: 'PASSED', startedAt: '', completedAt: '', durationMs: 42 })
  render(<ConnectionTestButton connectionUuid="connection" />)
  fireEvent.click(screen.getByRole('button', { name: 'Test Connection' }))
  await vi.waitFor(() => expect(notifyFeedback).toHaveBeenCalledWith('Connection successful: 42 ms', 'success'))
  expect(test).toHaveBeenCalledWith('project', 'connection')
})
it('does not bubble its action into a clickable record', async () => {
  vi.spyOn(topologyApi, 'testConnection').mockResolvedValue({ uuid: 'test', attemptNumber: 1, outcome: 'PASSED', startedAt: '', completedAt: '', durationMs: 42 })
  const open = vi.fn()
  const { container } = render(<div role="button" onClick={open}><ConnectionTestButton connectionUuid="connection" /></div>)
  fireEvent.click(container.querySelector('button')!)
  expect(open).not.toHaveBeenCalled()
  await vi.waitFor(() => expect(notifyFeedback).toHaveBeenCalledWith('Connection successful: 42 ms', 'success'))
})
it('reports the real failure message through the shared toast and allows retry', async () => {
  vi.spyOn(topologyApi, 'testConnection').mockRejectedValue(new Error('offline'))
  render(<ConnectionTestButton connectionUuid="connection" />)
  fireEvent.click(screen.getByRole('button', { name: 'Test Connection' }))
  await vi.waitFor(() => expect(notifyFeedback).toHaveBeenCalledWith('offline', 'error'))
  expect(screen.getByRole('button', { name: 'Test Connection' })).toBeEnabled()
})
