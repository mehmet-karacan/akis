import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../../core/i18n'
import { CurrentProjectProvider } from '../projects/CurrentProjectContext'
import { executionApi } from '../execution/api'
import { PublicationDetailPage } from './PublicationDetailPage'
import type { Publication } from './types'

vi.mock('../execution/api', () => ({ executionApi: { startRun: vi.fn(async () => ({ runUuid: 'run' })) } }))
vi.mock('./useRemoteData', () => ({ useRemoteData: () => ({ data: publication, loading: false, error: null, setData: vi.fn(), reload: vi.fn() }) }))
let publication: Publication
const fixture = (automatic: boolean): Publication => ({ uuid: 'publication', scenarioUuid: 'scenario', definitionUuid: 'definition', definitionVersionUuid: 'version', environmentUuid: 'uat', environmentCode: 'UAT', environmentRisk: 'DUSUK', publicationNumber: 1, status: 'AKTIF', releaseHash: 'hash', dependencySummary: '', physicalManifest: { stagedPlan: { options: automatic ? { resourceMode: 'AUTO' } : { batchRows: 500 } } }, publishedAt: null, createdAt: '2026-10-22T10:00:00Z', version: 1 })
const page = () => <MemoryRouter><CurrentProjectProvider projectUuid="project"><PublicationDetailPage /></CurrentProjectProvider></MemoryRouter>
beforeEach(async () => { await i18n.changeLanguage('en');vi.clearAllMocks();publication=fixture(true) })

it('starts an AUTO publication without asking for or supplying a batch override', async () => {
  render(page())
  expect(screen.queryByRole('spinbutton', { name: 'Batch (rows)' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Run' }))
  await waitFor(() => expect(executionApi.startRun).toHaveBeenCalledWith('project', 'publication', expect.any(String), undefined))
})

it('discards a legacy sizing override when navigating to an AUTO publication', async () => {
  publication=fixture(false)
  const rendered=render(page())
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Batch (rows)' }), { target: { value: '4000' } })
  publication=fixture(true)
  rendered.rerender(page())
  fireEvent.click(screen.getByRole('button', { name: 'Run' }))
  await waitFor(() => expect(executionApi.startRun).toHaveBeenCalledWith('project', 'publication', expect.any(String), undefined))
})
