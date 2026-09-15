import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { executionApi } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { RunDetailPage } from './RunDetailPage'
import { RunStatusBadge } from './RunStatusBadge'
import type { RunRecord } from './types'
vi.mock('../operations/api', () => ({ operationsApi: { getPublication: vi.fn(async () => null) } }))

vi.mock('./api', () => ({
  executionApi: {
    getRun: vi.fn(),
    listEvents: vi.fn(),
    listEventPage: vi.fn(),
    listSteps: vi.fn(),
    cancelRun: vi.fn(),
  },
  isExecutionDisabled: vi.fn(() => false),
}))

const run: RunRecord = {
  jobRequestUuid: 'job-request-id',
  runUuid: 'run-id',
  publicationUuid: 'publication-id',
  attemptNumber: 1,
  startType: 'MANUAL',
  status: 'BASARILI',
  releaseHash: 'release-hash-value',
  planHash: 'plan-hash-value',
  createdAt: '2026-09-11T08:00:00Z',
  startedAt: '2026-09-11T08:01:00Z',
  finishedAt: '2026-09-11T08:02:00Z',
  cancellationRequestedAt: null,
  allowedActions: [
    { action: 'CANCEL', allowed: false, reasonCode: 'RUN_NOT_QUEUED' },
    { action: 'START_NEW_ATTEMPT', allowed: false, reasonCode: 'NOT_SUPPORTED' },
    { action: 'RESUME', allowed: false, reasonCode: 'NOT_SUPPORTED' },
  ],
}

function renderRunDetail() {
  return render(
    <MemoryRouter initialEntries={['/projects/project-id/runs/run-id']}>
      <Routes>
        <Route path="/projects/:projectUuid/runs/:runUuid" element={<RunDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('execution UI states', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.mocked(executionApi.getRun).mockResolvedValue(run)
    vi.mocked(executionApi.listEventPage).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
    vi.mocked(executionApi.listSteps).mockResolvedValue([])
    await i18n.changeLanguage('en')
  })

  it('explains that manual requests are disabled without hiding inspection access', () => {
    render(<ExecutionDisabledNotice />)

    expect(screen.getByText('Execution requests are disabled')).toBeInTheDocument()
    expect(screen.getByText(/Existing runs and recorded events remain available/)).toBeInTheDocument()
  })

  it('renders a canonical running status as static recorded state', () => {
    render(<RunStatusBadge status="CALISIYOR" />)

    const badge = screen.getByText('Running')
    expect(badge).toHaveClass('run-status--calisiyor')
    expect(badge).not.toHaveClass('ops-spin')
    expect(badge).not.toHaveAttribute('aria-busy')
  })

  it('localizes every canonical control-plane status', () => {
    const statuses = [
      'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
      'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
      'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
      'BASARILI', 'BASARISIZ', 'IPTAL',
    ]

    for (const status of statuses) {
      const { unmount } = render(<RunStatusBadge status={status} />)
      expect(screen.queryByText(status)).not.toBeInTheDocument()
      unmount()
    }
  })

  it('shows the result without technical identifiers or attempts', async () => {
    renderRunDetail()
    expect(await screen.findByText('Succeeded')).toBeInTheDocument()
    expect(screen.queryByText('release-hash-value')).not.toBeInTheDocument()
    expect(screen.queryByText('plan-hash-value')).not.toBeInTheDocument()
    expect(screen.queryByText('Attempt')).not.toBeInTheDocument()
  })

  it('localizes the result in Turkish', async () => {
    await i18n.changeLanguage('tr')
    renderRunDetail()
    expect(await screen.findByText('Başarılı')).toBeInTheDocument()
    expect(screen.queryByText('Teknik tanımlayıcılar')).not.toBeInTheDocument()
  })

  it('opens run evidence in a panel with a run tree and transfer row metrics', async () => {
    vi.mocked(executionApi.listSteps).mockResolvedValue([
      { uuid: 'read', parentUuid: null, code: 'READ_SOURCE', type: 'PROCEDURE', ordinal: 1, name: 'Read rows', status: 'BASARILI', connectionRole: 'SOURCE', risk: 'READ_ONLY', startedAt: null, finishedAt: null, rowCount: 33, byteCount: 1200, errorCode: null },
      { uuid: 'insert', parentUuid: null, code: 'STEP_A', type: 'PROCEDURE', ordinal: 2, name: 'Write rows', status: 'BASARILI', connectionRole: 'TARGET', risk: 'DML', startedAt: null, finishedAt: null, rowCount: 33, byteCount: 0, errorCode: null, logCounter: 'INSERT', transactionState: 'COMMITTED' },
    ])
    const close = vi.fn()
    render(<MemoryRouter initialEntries={['/projects/project-id/runs/run-id']}><Routes><Route path="/projects/:projectUuid/runs/:runUuid" element={<RunDetailPage runUuidOverride="run-id" panel onClose={close} objectName="Customer Load" />} /></Routes></MemoryRouter>)

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAccessibleName('Run detail')
    expect(await screen.findByRole('heading', { name: 'Customer Load' })).toBeInTheDocument()
    expect(await screen.findByText('1. Read rows')).toBeInTheDocument()
    expect(screen.getAllByText('Rows Selected').length).toBeGreaterThan(0)
    expect(screen.getByText('Rows Inserted')).toBeInTheDocument()
    expect(screen.getAllByText('33').length).toBeGreaterThanOrEqual(2)

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(close).toHaveBeenCalledOnce()
  })
})
