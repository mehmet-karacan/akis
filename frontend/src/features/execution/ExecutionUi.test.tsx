import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { executionApi } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { RunDetailPage } from './RunDetailPage'
import { RunStatusBadge } from './RunStatusBadge'
import type { RunRecord } from './types'

vi.mock('./api', () => ({
  executionApi: {
    getRun: vi.fn(),
    listEvents: vi.fn(),
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
    vi.mocked(executionApi.listEvents).mockResolvedValue([])
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

  it('shows release and plan hashes as distinct copyable values', async () => {
    renderRunDetail()

    const releaseHash = await screen.findByText('release-hash-value')
    const planHash = screen.getByText('plan-hash-value')

    expect(screen.getByText('Release hash')).toBeInTheDocument()
    expect(screen.getByText('Plan hash')).toBeInTheDocument()
    expect(releaseHash.closest('.ops-copy-value')?.querySelector('button')).toHaveAccessibleName('Copy')
    expect(planHash.closest('.ops-copy-value')?.querySelector('button')).toHaveAccessibleName('Copy')
  })

  it('localizes both hash labels in Turkish', async () => {
    await i18n.changeLanguage('tr')
    renderRunDetail()

    expect(await screen.findByText('Sürüm özeti')).toBeInTheDocument()
    expect(screen.getByText('Plan özeti')).toBeInTheDocument()
  })
})
