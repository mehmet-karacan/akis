import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { ExportMenu } from './ExportMenu'
import type { ExportJob } from './exportTypes'

const createJob = vi.fn()
const getStatus = vi.fn()

vi.mock('./exportApi', () => ({
  exportApi: {
    createJob: (...args: unknown[]) => createJob(...args),
    getStatus: (...args: unknown[]) => getStatus(...args),
  },
  downloadBlob: vi.fn(),
}))

function renderMenu(props: Parameters<typeof ExportMenu>[0]) {
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <ExportMenu {...props} />
    </ConfigProvider>,
  )
}

describe('ExportMenu', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('tr')
    createJob.mockReset()
    getStatus.mockReset().mockResolvedValue({ uuid: 'e1', status: 'COMPLETED', processedRows: 10, resultRows: 10, byteSize: 100 })
  })
  it('renders an export button with scope options', async () => {
    renderMenu({ projectUuid: 'p1', dataset: 'runs', resourceId: 'run-history' })
    fireEvent.click(screen.getByRole('button', { name: 'Dışa Aktar' }))
    expect(await screen.findByRole('menuitem', { name: 'Filtrelenmiş sonuç' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Görünen satırlar' })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Tüm kayıtlar' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Seçili kayıtlar' })).not.toBeInTheDocument()
  })

  it('does not render export for catalogs without an explicit export contract', () => {
    renderMenu({ projectUuid: 'p1', dataset: 'topology', resourceId: 'connections' })
    expect(screen.queryByRole('button', { name: 'Dışa Aktar' })).not.toBeInTheDocument()
  })

  it('creates a filtered job, emits an event and displays its downloadable result', async () => {
    const job: ExportJob = {
      uuid: 'e1', providerId: 'runs', resourceId: 'run-history', scope: 'FILTERED',
      status: 'QUEUED', processedRows: 0, resultRows: 0, byteSize: 0,
      expiryAt: new Date().toISOString(), createdAt: new Date().toISOString(),
    }
    createJob.mockResolvedValueOnce(job)
    const listener = vi.fn()
    window.addEventListener('akis:export-job-created', listener)

    renderMenu({ projectUuid: 'p1', dataset: 'runs', resourceId: 'run-history', includeDetailsDefault: true, filters: [{ field: 'query', operator: 'contains', value: 'test' }] })
    fireEvent.click(screen.getByRole('button', { name: 'Dışa Aktar' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Filtrelenmiş sonuç' }))

    await waitFor(() => expect(createJob).toHaveBeenCalledOnce())
    const call = createJob.mock.calls[0] as [string, { providerId: string; resourceId: string; scope: string; filters: unknown[] }]
    expect(call[0]).toBe('p1')
    expect(call[1].providerId).toBe('runs')
    expect(call[1].resourceId).toBe('run-history')
    expect(call[1].scope).toBe('FILTERED')
    expect(call[1]).toMatchObject({ locale: 'tr', includeDetails: true })
    expect(call[1].filters).toEqual([{ field: 'query', operator: 'contains', value: 'test' }])
    await waitFor(() => expect(listener).toHaveBeenCalled())
    window.removeEventListener('akis:export-job-created', listener)
    expect(await screen.findByRole('button', { name: 'JSON İndir' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Aktarma Durumu' }))
    expect(await screen.findByRole('button', { name: 'JSON İndir' })).toBeInTheDocument()
  })

  it('shows creation failures and lets the user start again', async () => {
    createJob.mockRejectedValueOnce(new Error('Export unavailable')).mockResolvedValue({ uuid: 'e1' })
    renderMenu({ projectUuid: 'p1', dataset: 'runs', resourceId: 'run-history' })
    fireEvent.click(screen.getByRole('button', { name: 'Dışa Aktar' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Tüm kayıtlar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Export unavailable')
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    fireEvent.click(screen.getByRole('button', { name: 'Dışa Aktar' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Tüm kayıtlar' }))
    expect(await screen.findByRole('button', { name: 'JSON İndir' })).toBeInTheDocument()
    expect(createJob).toHaveBeenCalledTimes(2)
  })

  it('uses the application language for controls and requests', async () => {
    await i18n.changeLanguage('en')
    createJob.mockResolvedValue({ uuid: 'e1' })
    renderMenu({ projectUuid: 'p1', dataset: 'models', resourceId: 'data-objects' })
    fireEvent.click(screen.getByRole('button', { name: 'Export' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'All records' }))
    expect(await screen.findByRole('button', { name: 'Download JSON' })).toBeInTheDocument()
    expect(createJob).toHaveBeenCalledWith('p1', expect.objectContaining({ locale: 'en', scope: 'ALL' }))
  })
})
