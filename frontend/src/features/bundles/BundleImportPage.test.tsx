import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { bundleApi } from './api'
import { BundleImportPage } from './BundleImportPage'
import type { ProjectBundleDocument } from './types'

vi.mock('./api', () => ({
  bundleApi: {
    validate: vi.fn(),
    importProject: vi.fn(),
  },
}))

const document: ProjectBundleDocument = {
  format: 'akis.project-bundle',
  formatVersion: 1,
  schemaVersion: 1,
  checksum: 'a'.repeat(64),
  exportedAt: '2026-09-11T00:00:00Z',
  project: { code: 'DEMO', status: 'AKTIF', name: 'Demo', description: null },
  folders: [],
  definitions: [],
  topology: { sanitized: true },
}

describe('bundle import gate', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    await i18n.changeLanguage('en')
  })

  it('keeps real import locked until server validation and dry-run succeed', async () => {
    vi.mocked(bundleApi.validate).mockResolvedValue({
      valid: true,
      counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      issues: [],
    })
    vi.mocked(bundleApi.importProject)
      .mockResolvedValueOnce({
        imported: false,
        dryRun: true,
        projectCode: 'DEMO',
        projectUuid: null,
        counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      })
      .mockResolvedValueOnce({
        imported: true,
        dryRun: false,
        projectCode: 'DEMO',
        projectUuid: '11111111-1111-1111-1111-111111111111',
        counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      })

    render(<MemoryRouter><BundleImportPage /></MemoryRouter>)
    const file = new File([JSON.stringify(document)], 'demo.json', { type: 'application/json' })
    fireEvent.change(screen.getByLabelText('Choose bundle file', { selector: 'input' }), {
      target: { files: [file] },
    })

    expect(await screen.findByText('Server validation passed.')).toBeInTheDocument()
    const importButton = screen.getByRole('button', { name: 'Import project' })
    expect(importButton).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Run import preview' }))
    await waitFor(() => expect(importButton).toBeEnabled())
    expect(bundleApi.importProject).toHaveBeenNthCalledWith(1, document, 'FAIL', true)

    fireEvent.click(importButton)
    expect(screen.getByRole('dialog', { name: 'Confirm project import' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm import' }))
    expect(await screen.findByRole('link', { name: 'Open imported project' })).toHaveAttribute(
      'href', '/projects/11111111-1111-1111-1111-111111111111',
    )
    expect(bundleApi.importProject).toHaveBeenNthCalledWith(2, document, 'FAIL', false)
  })

  it('locks retries when the real import outcome is unknown', async () => {
    vi.mocked(bundleApi.validate).mockResolvedValue({
      valid: true,
      counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      issues: [],
    })
    vi.mocked(bundleApi.importProject)
      .mockResolvedValueOnce({
        imported: false,
        dryRun: true,
        projectCode: 'DEMO',
        projectUuid: null,
        counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      })
      .mockRejectedValueOnce(new TypeError('network response lost'))

    render(<MemoryRouter><BundleImportPage /></MemoryRouter>)
    const file = new File([JSON.stringify(document)], 'demo.json', { type: 'application/json' })
    fireEvent.change(screen.getByLabelText('Choose bundle file', { selector: 'input' }), {
      target: { files: [file] },
    })
    expect(await screen.findByText('Server validation passed.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Run import preview' }))
    const importButton = screen.getByRole('button', { name: 'Import project' })
    await waitFor(() => expect(importButton).toBeEnabled())
    fireEvent.click(importButton)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm import' }))

    expect(await screen.findByText(/response was not confirmed/i)).toBeInTheDocument()
    expect(importButton).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Run import preview' })).toBeDisabled()
  })
})
