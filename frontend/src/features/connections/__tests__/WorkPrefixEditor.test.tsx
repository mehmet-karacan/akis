import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import * as client from '../../../core/api/client'
import { WorkPrefixEditor } from '../WorkPrefixEditor'

const initial = { prefixes: { loading: 'C$', integration: 'I$', error: 'E$' }, version: 0, origin: 'PLATFORM' }
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })
it('saves naming metadata without testing a connection', async () => {
  const request = vi.spyOn(client, 'apiRequest').mockResolvedValue(initial)
  render(<WorkPrefixEditor projectUuid="p" subjectUuid="c" canWrite />)
  const input = screen.getByLabelText('Loading / LKM')
  await waitFor(() => expect(input).toBeEnabled())
  fireEvent.change(input, { target: { value: 'load' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save Prefixes' }))
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
  expect(request.mock.calls[1]![0]).toBe('/api/v1/projects/p/connections/c/work-prefixes')
  expect(JSON.parse(request.mock.calls[1]![1]!.body as string)).toEqual({ expectedVersion: 0, prefixes: { ...initial.prefixes, loading: 'LOAD' } })
})
it('blocks duplicate prefixes and denies read-only mutations', async () => {
  vi.spyOn(client, 'apiRequest').mockResolvedValue(initial)
  const view = render(<WorkPrefixEditor projectUuid="p" subjectUuid="c" canWrite />)
  await waitFor(() => expect(screen.getByLabelText('Loading / LKM')).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Loading / LKM'), { target: { value: 'I$' } })
  expect(screen.getByRole('button', { name: 'Save Prefixes' })).toBeDisabled()
  view.rerender(<WorkPrefixEditor projectUuid="p" subjectUuid="c" canWrite={false} />)
  expect(screen.queryByRole('button', { name: 'Save Prefixes' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('Loading / LKM')).toBeDisabled()
})
