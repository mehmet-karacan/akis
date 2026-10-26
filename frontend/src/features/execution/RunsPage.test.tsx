import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { selectAntOption } from '../../test/selectAntOption'
import { executionApi } from './api'
import { RunsPage } from './RunsPage'

vi.mock('../projects/CurrentProjectContext', () => ({ useCurrentProjectUuid: () => 'project-id' }))
vi.mock('../operations/api', () => ({ operationsApi: { listPublications: vi.fn(async () => []) } }))
vi.mock('./api', () => ({ executionApi: { searchRuns: vi.fn(), getOverview: vi.fn() } }))

const page = {
  total: 1, page: 0, size: 50,
  items: [{
    run: { runUuid: 'run-1', jobRequestUuid: 'job-1', publicationUuid: 'publication-1', attemptNumber: 1, startType: 'MANUAL', status: 'BASARILI', releaseHash: 'release', planHash: 'plan', createdAt: '2026-09-24T10:00:00Z', startedAt: '2026-09-24T10:00:00Z', finishedAt: '2026-09-24T10:00:02Z', cancellationRequestedAt: null, allowedActions: [] },
    definitionUuid: 'definition-1', definitionCode: 'LOAD_CUSTOMER', definitionName: 'Müşteri Aktarımı', definitionType: 'VARIABLE',
    environmentUuid: 'environment-1', environmentCode: 'TEST', environmentName: 'Test', environmentRisk: 'TEST', initiatorName: 'developer', scheduleCode: null,
    selectedRows: 123456, insertedRows: 123456, updatedRows: 5, deletedRows: 2,
  }],
}
const overview = { totalRuns: 123456, activeRuns: 2, queuedRuns: 3, succeededRuns: 123450, failedRuns: 1, selectedRowsExact: '123456', insertedRowsExact: '123456', updatedRowsExact: '5', deletedRowsExact: '2' }

describe('RunsPage', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    await i18n.changeLanguage('tr')
    vi.mocked(executionApi.searchRuns).mockResolvedValue(page)
    vi.mocked(executionApi.getOverview).mockResolvedValue(overview)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  })

  it('uses a single table view, exposes object type and keeps the operational summary compact', async () => {
    render(<MemoryRouter><RunsPage /></MemoryRouter>)

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Nesne türü/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Başlangıç Zamanı' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Bitiş Zamanı' })).toBeInTheDocument()
    expect(screen.getAllByText('Değişken').length).toBeGreaterThan(0)
    expect(screen.getByText('2 Saniye')).toBeInTheDocument()
    expect(screen.queryByLabelText('Görünüm')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Başlangıç Zamanı')).toHaveAttribute('step', '1')
    expect(screen.getByLabelText('Bitiş Zamanı')).toHaveAttribute('step', '1')
    expect(screen.queryByText('Operasyon Özeti')).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.getAllByText('123.456').length).toBeGreaterThan(0)
    const summary = screen.getByLabelText('Çalıştırma özeti')
    expect(summary).toHaveClass('ui-summary-strip')
    expect(summary.querySelectorAll('.ui-summary-card')).toHaveLength(9)
  })

  it('sends the selected date range and automatic trigger to the search API', async () => {
    render(<MemoryRouter><RunsPage /></MemoryRouter>)
    await screen.findByRole('table')

    fireEvent.change(screen.getByLabelText('Başlangıç Zamanı'), { target: { value: '2026-09-24T09:00:00' } })
    fireEvent.change(screen.getByLabelText('Bitiş Zamanı'), { target: { value: '2026-09-24T11:30:45' } })
    await selectAntOption(screen.getByLabelText('Tetikleyici'), 'Otomatik')
    fireEvent.submit(screen.getByLabelText('Tetikleyici').closest('form')!)

    await waitFor(() => expect(executionApi.searchRuns).toHaveBeenLastCalledWith('project-id', expect.objectContaining({
      view: 'RECENT',
      from: new Date('2026-09-24T09:00:00').toISOString(),
      to: new Date('2026-09-24T11:30:45').toISOString(),
      scheduled: true,
    })))
  })

  it('reloads immediately from the refresh action', async () => {
    render(<MemoryRouter><RunsPage /></MemoryRouter>)
    await screen.findByRole('table')

    const initial = vi.mocked(executionApi.searchRuns).mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: 'Yenile' }))

    await waitFor(() => expect(executionApi.searchRuns).toHaveBeenCalledTimes(initial + 1))
    await waitFor(() => expect(executionApi.getOverview).toHaveBeenCalledTimes(2))
  })

  it('reloads automatically on the configured interval', async () => {
    let intervalCallback: (() => void) | undefined
    const interval = vi.spyOn(window, 'setInterval').mockImplementation((callback, delay) => {
      if (delay === 15000) intervalCallback = callback as () => void
      return 1 as unknown as ReturnType<typeof window.setInterval>
    })
    vi.spyOn(window, 'clearInterval').mockImplementation(() => undefined)
    render(<MemoryRouter><RunsPage /></MemoryRouter>)
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('button', { name: 'Sürekli yenilemeyi aç veya kapat' }))
    await waitFor(() => expect(interval).toHaveBeenCalledWith(expect.any(Function), 15000))
    const beforeAutomatic = vi.mocked(executionApi.searchRuns).mock.calls.length
    await act(async () => { intervalCallback?.(); await Promise.resolve(); await Promise.resolve() })
    await waitFor(() => expect(executionApi.searchRuns).toHaveBeenCalledTimes(beforeAutomatic + 1))
  })
})
