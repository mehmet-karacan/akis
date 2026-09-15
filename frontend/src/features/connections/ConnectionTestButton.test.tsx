import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi } from '../topology/api'
import { ConnectionTestButton } from './ConnectionTestButton'

vi.mock('../projects/CurrentProjectContext', () => ({ useCurrentProjectUuid: () => 'project' }))
vi.mock('../../core/auth/ProjectAccessContext', () => ({ useProjectAccess: () => ({ can: () => true }) }))
beforeEach(async () => { await i18n.changeLanguage('en'); vi.restoreAllMocks() })
it('tests a saved connection without opening its details', async () => {
  const test = vi.spyOn(topologyApi, 'testConnectionVersion').mockResolvedValue({ uuid: 'test', connectionVersionUuid: 'version', attemptNumber: 1, outcome: 'PASSED', startedAt: '', completedAt: '', durationMs: 42 })
  render(<ConnectionTestButton connectionUuid="connection" versionUuid="version" />)
  fireEvent.click(screen.getByRole('button', { name: 'Test Connection' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Connection Successful')
  expect(test).toHaveBeenCalledWith('project', 'connection', 'version')
})
it('does not bubble its action into a clickable record', async () => {
  vi.spyOn(topologyApi, 'testConnectionVersion').mockResolvedValue({ uuid: 'test', connectionVersionUuid: 'version', attemptNumber: 1, outcome: 'PASSED', startedAt: '', completedAt: '', durationMs: 42 })
  const open = vi.fn()
  const { container } = render(<div role="button" onClick={open}><ConnectionTestButton connectionUuid="connection" versionUuid="version" /></div>)
  fireEvent.click(container.querySelector('button')!)
  expect(open).not.toHaveBeenCalled()
  expect(await screen.findByRole('status')).toHaveTextContent('Connection Successful')
})
it('reports transport failures inline and allows retry', async () => {
  vi.spyOn(topologyApi, 'testConnectionVersion').mockRejectedValue(new Error('offline'))
  render(<ConnectionTestButton connectionUuid="connection" versionUuid="version" />)
  fireEvent.click(screen.getByRole('button', { name: 'Test Connection' }))
  expect(await screen.findByRole('status')).toHaveTextContent('VPN')
  expect(screen.getByRole('button', { name: 'Test Connection' })).toBeEnabled()
})
