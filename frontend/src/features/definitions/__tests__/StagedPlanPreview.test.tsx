import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { operationsApi } from '../../operations/api'
import { StagedPlanPreview } from '../StagedPlanPreview'

const response = { plan: { physicalPlanHash: 'a'.repeat(64), steps: [{ id: 'LOAD', site: 'STAGING', operation: 'TRANSFER_JDBC', slot: 'WORK_SOURCE_1' }] }, executionVerified: false, message: 'Execution not verified' }
beforeEach(() => vi.restoreAllMocks())
it('previews only on request and returns the precise reviewed hash', async () => {
  const api = vi.spyOn(operationsApi, 'previewStagedPlan').mockResolvedValue(response)
  const ready = vi.fn()
  render(<StagedPlanPreview projectUuid="p" scenarioUuid="s" environmentUuid="e" tr={false} onReady={ready} />)
  expect(api).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Preview Execution Plan' }))
  expect(await screen.findByText('TRANSFER_JDBC')).toBeInTheDocument()
  expect(ready).toHaveBeenCalledWith(response.plan.physicalPlanHash)
})
it('discards a response for an environment that is no longer selected', async () => {
  let resolve!: (value: typeof response) => void
  vi.spyOn(operationsApi, 'previewStagedPlan').mockReturnValue(new Promise(done => { resolve = done }))
  const ready = vi.fn()
  const view = render(<StagedPlanPreview projectUuid="p" scenarioUuid="s" environmentUuid="test" tr={false} onReady={ready} />)
  fireEvent.click(screen.getByRole('button', { name: 'Preview Execution Plan' }))
  view.rerender(<StagedPlanPreview projectUuid="p" scenarioUuid="s" environmentUuid="prod" tr={false} onReady={ready} />)
  await act(async () => resolve(response))
  await waitFor(() => expect(ready).not.toHaveBeenCalled())
  expect(screen.queryByText('TRANSFER_JDBC')).not.toBeInTheDocument()
})
