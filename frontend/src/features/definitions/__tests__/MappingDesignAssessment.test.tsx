import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import * as client from '../../../core/api/client'
import { MappingDesignAssessment } from '../MappingDesignAssessment'
import { DEFAULT_MAPPING } from '../defaults'

const supported = { shapeSupported: true, executionVerified: false, capability: 'ORACLE_TABLE_COPY_V1', reasonCode: null, maximumSourceRows: 1000, remainingChecks: ['VERSIONED_BINDINGS'] }
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })
it('checks only on demand and labels physical execution as unverified', async () => {
  const request = vi.spyOn(client, 'apiRequest').mockResolvedValue(supported)
  render(<MappingDesignAssessment projectUuid="p" value={DEFAULT_MAPPING} />)
  expect(request).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Check Runtime Compatibility' }))
  expect(await screen.findByText('Compatible Shape — Execution Not Verified')).toBeInTheDocument()
  expect(JSON.parse(request.mock.calls[0]![1]!.body as string).content.datasets[0]).not.toHaveProperty('name')
})
it('discards a stale response after the definition changes', async () => {
  let resolve!: (value: unknown) => void
  vi.spyOn(client, 'apiRequest').mockReturnValue(new Promise(done => { resolve = done }))
  const view = render(<MappingDesignAssessment projectUuid="p" value={DEFAULT_MAPPING} />)
  fireEvent.click(screen.getByRole('button', { name: 'Check Runtime Compatibility' }))
  view.rerender(<MappingDesignAssessment projectUuid="p" value={{ ...DEFAULT_MAPPING, columnMappings: [] }} />)
  await act(async () => resolve(supported))
  expect(screen.queryByText('Compatible Shape — Execution Not Verified')).not.toBeInTheDocument()
})
it('offers explicit legacy upgrade and shows rejection without a success badge', async () => {
  const upgrade = vi.fn()
  vi.spyOn(client, 'apiRequest').mockResolvedValue({ ...supported, shapeSupported: false, reasonCode: 'UNSUPPORTED_WRITE_STRATEGY' })
  render(<MappingDesignAssessment projectUuid="p" value={DEFAULT_MAPPING} schemaVersion={1} onUpgrade={upgrade} />)
  fireEvent.click(screen.getByRole('button', { name: 'Upgrade Draft to Version 2' }))
  expect(upgrade).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: 'Check Runtime Compatibility' }))
  await waitFor(() => expect(screen.getByText('Definition Only — Runtime Incompatibility')).toBeInTheDocument())
})
