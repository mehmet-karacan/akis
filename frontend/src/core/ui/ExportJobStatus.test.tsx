import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ExportJobStatus } from './ExportJobStatus'
import type { ExportJob } from './exportTypes'
import i18n from '../i18n'

const getStatus = vi.fn()
const download = vi.fn()
const cancel = vi.fn()
const downloadUrl = vi.fn()

vi.mock('./exportApi', () => ({
  exportApi: {
    getStatus: (...args: unknown[]) => getStatus(...args),
    download: (...args: unknown[]) => download(...args),
    cancel: (...args: unknown[]) => cancel(...args),
    downloadUrl: (...args: unknown[]) => downloadUrl(...args),
  },
  downloadBlob: vi.fn(),
}))

describe('ExportJobStatus', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('tr')
    downloadUrl.mockReset().mockReturnValue('/api/export/download')
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.restoreAllMocks()
    vi.useRealTimers()
    getStatus.mockReset()
    download.mockReset()
    cancel.mockReset()
  })

  it('polls until completion and shows a download button', async () => {
    const queued: ExportJob = {
      uuid: 'e1', providerId: 'runs', resourceId: 'run-history', scope: 'VISIBLE',
      status: 'QUEUED', processedRows: 0, resultRows: 0, byteSize: 0,
      expiryAt: new Date().toISOString(), createdAt: new Date().toISOString(),
    }
    const completed: ExportJob = {
      ...queued, status: 'COMPLETED', processedRows: 100, resultRows: 50, byteSize: 1024,
    }
    getStatus.mockResolvedValueOnce(queued).mockResolvedValueOnce(completed)

    render(<ExportJobStatus projectUuid="p1" exportUuid="e1" />)

    await vi.waitFor(() => expect(screen.getByText('Dışa Aktarma · Tamamlandı')).toBeInTheDocument(), { timeout: 5000 })
    expect(screen.getByText('Dışa Aktarılan Satır: 50')).toBeInTheDocument()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    fireEvent.click(screen.getByRole('button', { name: 'JSON İndir' }))
    expect(downloadUrl).toHaveBeenCalledWith('p1', 'e1')
    expect(click).toHaveBeenCalledOnce()
    await act(() => vi.advanceTimersByTimeAsync(4000))
    expect(getStatus).toHaveBeenCalledTimes(2)
  })

  it('shows an error when status check fails', async () => {
    getStatus.mockRejectedValueOnce(new Error('Network error'))
    render(<ExportJobStatus projectUuid="p1" exportUuid="e1" />)

    await waitFor(() => expect(screen.getByText('Network error')).toBeInTheDocument(), { timeout: 5000 })
    getStatus.mockResolvedValue({ status: 'COMPLETED', processedRows: 2, resultRows: 2, byteSize: 0 })
    fireEvent.click(screen.getByRole('button', { name: 'Yeniden Dene' }))
    expect(await screen.findByRole('button', { name: 'JSON İndir' })).toBeInTheDocument()
    expect(screen.queryByText('Network error')).not.toBeInTheDocument()
  })

  it('cancels an active job and refreshes its terminal status', async () => {
    getStatus.mockResolvedValueOnce({ status: 'RUNNING', processedRows: 0, resultRows: 0, byteSize: 0 })
      .mockResolvedValue({ status: 'CANCELLED', processedRows: 0, resultRows: 0, byteSize: 0 })
    cancel.mockResolvedValue(undefined)
    render(<ExportJobStatus projectUuid="p1" exportUuid="e1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'İptal Et' }))
    expect(await screen.findByText('Dışa Aktarma · İptal Edildi')).toBeInTheDocument()
    expect(cancel).toHaveBeenCalledExactlyOnceWith('p1', 'e1')
    expect(screen.queryByRole('button', { name: 'JSON İndir' })).not.toBeInTheDocument()
  })

  it('clears scheduled polling on unmount', async () => {
    getStatus.mockResolvedValue({ status: 'RUNNING', processedRows: 0, resultRows: 0, byteSize: 0 })
    const { unmount } = render(<ExportJobStatus projectUuid="p1" exportUuid="e1" />)
    await screen.findByText('Dışa Aktarma · Çalışıyor')
    unmount()
    await act(() => vi.advanceTimersByTimeAsync(4000))
    expect(getStatus).toHaveBeenCalledOnce()
  })
})
