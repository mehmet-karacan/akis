import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { CurrentProjectProvider } from '../projects/CurrentProjectContext'
import { executionApi } from './api'
import { RunDetailPage } from './RunDetailPage'
import type { RunRecord, RunStep } from './types'
import type { KmRunData } from './KmRunDetails'

vi.mock('../operations/api', () => ({ operationsApi: { getPublication: vi.fn(async () => null) } }))
vi.mock('./api', () => ({ executionApi: {
  getRun: vi.fn(), listSteps: vi.fn(), listEventPage: vi.fn(), getKmDetails: vi.fn(), listChunks: vi.fn(),
} }))

const run: RunRecord = {
  jobRequestUuid: 'job', runUuid: 'run', publicationUuid: 'publication', attemptNumber: 1, startType: 'MANUAL',
  status: 'CALISIYOR', releaseHash: 'release', planHash: 'plan', createdAt: '', startedAt: null, finishedAt: null,
  cancellationRequestedAt: null, allowedActions: [],
}
const parent: RunStep = {
  uuid: 'package-step', parentUuid: null, code: 'LOAD', type: 'PAKET', ordinal: 1, name: 'Load mapping',
  status: 'CALISIYOR', connectionRole: null, risk: null, startedAt: null, finishedAt: null,
  rowCount: null, byteCount: null, errorCode: null, childRunUuid: 'child',
}
const snapshot = (bytes: number): KmRunData => ({ workObjects: [], steps: [{
  generation: 1, ordinal: 1, stepCode: 'LOAD', operation: 'TRANSFER_JDBC', site: 'STAGING', slot: 'WORK_SOURCE_1',
  state: 'RUNNING', affectedRows: 500, errorCode: null, startedAt: null, completedAt: null,
  transferProgress: { rows: 500, bytes, elapsedSeconds: 10, readSeconds: 2, writeSeconds: 7, commitSeconds: 1 },
}] })

async function openDetail() {
  await act(async () => { render(<MemoryRouter><CurrentProjectProvider projectUuid="project"><RunDetailPage runUuidOverride="run" /></CurrentProjectProvider></MemoryRouter>) })
}

describe('live run transfer progress', () => {
  beforeEach(async () => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    await i18n.changeLanguage('en')
    vi.mocked(executionApi.getRun).mockResolvedValue(run)
    vi.mocked(executionApi.listSteps).mockResolvedValue([])
    vi.mocked(executionApi.listEventPage).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
    vi.mocked(executionApi.listChunks).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
    vi.mocked(executionApi.getKmDetails).mockResolvedValue(snapshot(1048576))
  })
  afterEach(() => vi.useRealTimers())

  it('refreshes a running mapping from persisted snapshots', async () => {
    await openDetail()
    expect(within(screen.getByRole('region', { name: 'Transfer progress' })).getByText('1 MiB')).toBeInTheDocument()
    vi.mocked(executionApi.getKmDetails).mockResolvedValue(snapshot(2097152))
    await act(async () => { await vi.advanceTimersByTimeAsync(4000) })
    expect(within(screen.getByRole('region', { name: 'Transfer progress' })).getByText('2 MiB')).toBeInTheDocument()
  })

  it('renders and polls a package child even when its step list arrives after the run', async () => {
    let resolveSteps!: (steps: RunStep[]) => void
    vi.mocked(executionApi.listSteps).mockImplementation(async (_, uuid) => uuid === 'child' ? [] : [parent])
    vi.mocked(executionApi.listSteps).mockReturnValueOnce(new Promise(resolve => { resolveSteps = resolve }))
    let bytes = 1048576
    vi.mocked(executionApi.getKmDetails).mockImplementation(async (_, uuid) => uuid === 'child' ? snapshot(bytes) : { steps: [], workObjects: [] })
    await openDetail()
    await act(async () => { resolveSteps([parent]) })
    const packageNode = screen.getByRole('treeitem', { name: /Load mapping/ })
    await act(async () => {
      if (packageNode.getAttribute('aria-expanded') === 'false') fireEvent.click(packageNode.querySelector('.ant-tree-switcher')!)
    })
    await act(async () => { fireEvent.click(screen.getByText('1. TRANSFER_JDBC')) })
    expect(within(screen.getByRole('region', { name: 'Transfer progress' })).getByText('1 MiB')).toBeInTheDocument()
    bytes = 2097152
    await act(async () => { await vi.advanceTimersByTimeAsync(4000) })
    expect(within(screen.getByRole('region', { name: 'Transfer progress' })).getByText('2 MiB')).toBeInTheDocument()
  })
})
