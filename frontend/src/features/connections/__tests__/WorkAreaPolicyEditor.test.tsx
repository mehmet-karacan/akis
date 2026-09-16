import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import * as client from '../../../core/api/client'
import { WorkAreaPolicyEditor } from '../WorkAreaPolicyEditor'

const initial = { policy: { enabled: false, allowSameSchema: false, maxObjects: 10, maxRowsPerRun: 100000, maxBytesPerRun: 268435456, retentionHours: 24 }, version: 0 }
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })

it('requires explicit opt-in and saves policy without connection test or DDL', async () => {
  const request = vi.spyOn(client, 'apiRequest').mockResolvedValue(initial)
  render(<WorkAreaPolicyEditor projectUuid="p" schemaUuid="s" canWrite />)
  const enabled = await screen.findByRole('switch', { name: 'Allow Managed Work Tables' })
  expect(enabled).not.toBeChecked()
  fireEvent.click(enabled)
  fireEvent.click(screen.getByRole('button', { name: 'Save Work Area' }))
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
  expect(request.mock.calls[1]![0]).toBe('/api/v1/projects/p/physical-schemas/s/work-area-policy')
  expect(JSON.parse(request.mock.calls[1]![1]!.body as string)).toEqual({ expectedVersion: 0, policy: { ...initial.policy, enabled: true } })
})
it('read-only users cannot change or save policy', async () => {
  vi.spyOn(client, 'apiRequest').mockResolvedValue(initial)
  render(<WorkAreaPolicyEditor projectUuid="p" schemaUuid="s" canWrite={false} />)
  expect(await screen.findByRole('switch', { name: 'Allow Managed Work Tables' })).toBeDisabled()
  expect(screen.getByLabelText('Maximum Work Tables')).toBeDisabled()
  expect(screen.queryByRole('button', { name: 'Save Work Area' })).not.toBeInTheDocument()
})
it('load failure does not expose an editable fallback policy', async () => {
  vi.spyOn(client, 'apiRequest').mockRejectedValue(new Error('unavailable'))
  render(<WorkAreaPolicyEditor projectUuid="p" schemaUuid="s" canWrite />)
  await screen.findByText('Could not load work area settings.')
  expect(screen.getByRole('button', { name: 'Save Work Area' })).toBeDisabled()
  expect(screen.queryByRole('switch')).not.toBeInTheDocument()
})
