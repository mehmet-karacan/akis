import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import * as projectsApi from '../projects/projectsApi'
import * as topologyApi from '../topology/api'
import { bundleApi } from './api'
import { BundleImportPage } from './BundleImportPage'
import type { ProjectBundleDocument } from './types'

vi.mock('./api', () => ({
  bundleApi: {
    validate: vi.fn(),
    importProject: vi.fn(),
    planTargetImport: vi.fn(),
    importIntoTarget: vi.fn(),
  },
}))

vi.mock('../projects/projectsApi', () => ({
  listProjects: vi.fn(),
}))

vi.mock('../topology/api', () => ({
  topologyApi: {
    listConnections: vi.fn(),
    listPhysicalSchemas: vi.fn(),
    listLogicalSchemas: vi.fn(),
    listEnvironments: vi.fn(),
  },
}))

const document: ProjectBundleDocument = {
  format: 'akis.project-bundle',
  formatVersion: 2,
  schemaVersion: 2,
  checksum: 'a'.repeat(64),
  exportedAt: '2026-09-11T00:00:00Z',
  project: { code: 'DEMO', status: 'AKTIF', name: 'Demo', description: null },
  folders: [],
  definitions: [],
  topology: { sanitized: true, definitions: {} },
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
    fireEvent.change(screen.getByLabelText('Choose Bundle File', { selector: 'input' }), {
      target: { files: [file] },
    })

    expect(await screen.findByText('Server validation passed.')).toBeInTheDocument()
    const importButton = screen.getByRole('button', { name: 'Import Project' })
    expect(importButton).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Run Import Preview' }))
    await waitFor(() => expect(importButton).toBeEnabled())
    expect(bundleApi.importProject).toHaveBeenNthCalledWith(1, document, 'FAIL', true)

    fireEvent.click(importButton)
    expect(screen.getByRole('dialog', { name: 'Confirm project import' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Import' }))
    expect(await screen.findByRole('link', { name: 'Open Imported Project' })).toHaveAttribute(
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
    fireEvent.change(screen.getByLabelText('Choose Bundle File', { selector: 'input' }), {
      target: { files: [file] },
    })
    expect(await screen.findByText('Server validation passed.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Run Import Preview' }))
    const importButton = screen.getByRole('button', { name: 'Import Project' })
    await waitFor(() => expect(importButton).toBeEnabled())
    fireEvent.click(importButton)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Import' }))

    expect(await screen.findByText(/response was not confirmed/i)).toBeInTheDocument()
    expect(importButton).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Run Import Preview' })).toBeDisabled()
  })

  it('allows a same-code empty target and retries an uncertain v3 import with the same idempotency key', async () => {
    const v3Document: ProjectBundleDocument = {
      ...document,
      formatVersion: 3,
      schemaVersion: 3,
      producer: { application: 'akis', version: '1.0.0', commit: 'abc' },
      includedSections: ['topology', 'definitions'],
    }

    vi.mocked(bundleApi.validate).mockResolvedValue({
      valid: true,
      counts: { folders: 1, definitions: 2, drafts: 0, versions: 0 },
      issues: [],
    })

    const dependency = {
      type: 'CONNECTION' as const,
      sourceCode: 'SRC_DB',
      provider: 'ORACLE',
      required: true,
      mode: 'BIND_EXISTING' as const,
      targetUuid: null,
      targetCode: null,
      resolved: false,
      message: 'Select a matching connection',
    }

    vi.mocked(bundleApi.planTargetImport).mockResolvedValue({
      valid: true,
      targetProjectUuid: 'target-uuid',
      targetVersion: 3,
      bundleChecksum: 'checksum',
      planDigest: 'plan-digest-42',
      counts: { folders: 1, definitions: 2, drafts: 0, versions: 0 },
      changes: { definitions: 2, folders: 1 },
      globalDependencies: [dependency],
      issues: [],
    })

    vi.mocked(bundleApi.importIntoTarget).mockRejectedValueOnce(new TypeError('network response lost')).mockResolvedValueOnce({
      imported: true,
      replayed: true,
      targetProjectUuid: 'target-uuid',
      targetVersion: 4,
      bundleChecksum: 'checksum',
      planDigest: 'plan-digest-42',
      counts: { folders: 1, definitions: 2, drafts: 0, versions: 0 },
    })

    const randomUUID = vi.fn().mockReturnValueOnce('idem-key-1234').mockReturnValueOnce('idem-key-5678')
    vi.stubGlobal('crypto', { randomUUID })

    vi.mocked(projectsApi.listProjects).mockResolvedValue([
      { uuid: 'target-uuid', code: 'DEMO', status: 'AKTIF', name: 'Target Project', description: null, version: 3, createdAt: '2026-01-01T00:00:00Z' },
    ])
    vi.mocked(topologyApi.topologyApi.listConnections).mockResolvedValue([
      { uuid: 'conn-uuid', code: 'SRC_DB', name: 'Source DB', databaseType: 'ORACLE', mode: 'JDBC', hasPassword: true, fetchSize: 100, batchSize: 100, connectTimeoutMs: 1000, readTimeoutMs: 1000, queryTimeoutSeconds: 30, status: 'AKTIF' },
    ])
    vi.mocked(topologyApi.topologyApi.listPhysicalSchemas).mockResolvedValue([])
    vi.mocked(topologyApi.topologyApi.listLogicalSchemas).mockResolvedValue([])
    vi.mocked(topologyApi.topologyApi.listEnvironments).mockResolvedValue([])

    render(<MemoryRouter><BundleImportPage /></MemoryRouter>)
    const file = new File([JSON.stringify(v3Document)], 'demo-v3.json', { type: 'application/json' })
    fireEvent.change(screen.getByLabelText('Choose Bundle File', { selector: 'input' }), {
      target: { files: [file] },
    })

    expect(await screen.findByText('Server validation passed.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Choose target project' }))
    await waitFor(() => expect(screen.getByTestId('bundle-target-select')).toBeInTheDocument())

    fireEvent.mouseDown(screen.getByTestId('bundle-target-select'))
    fireEvent.click(screen.getByText('Target Project (DEMO)'))

    await waitFor(() => expect(topologyApi.topologyApi.listConnections).toHaveBeenCalledWith('target-uuid'))

    fireEvent.click(screen.getByRole('button', { name: 'Preview plan' }))
    expect(await screen.findByText('Select a matching connection')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByTestId('bundle-target-CONNECTION-SRC_DB'))
    fireEvent.click(screen.getByText('Source DB (SRC_DB)'))
    expect(screen.getByText(/Importing into a new project does not isolate business data/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Preview plan' }))
    expect(await screen.findByText('Import plan is valid.')).toBeInTheDocument()

    const importButton = screen.getByRole('button', { name: 'Import into target project' })
    expect(importButton).toBeEnabled()
    fireEvent.click(importButton)

    expect(screen.getByRole('dialog', { name: 'Confirm import into target project' })).toBeInTheDocument()
    expect(bundleApi.importIntoTarget).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Import into target' }))

    await waitFor(() => expect(bundleApi.importIntoTarget).toHaveBeenCalledWith(
      'target-uuid',
      expect.objectContaining({ planDigest: 'plan-digest-42', targetVersion: 3 }),
      'idem-key-1234',
    ))

    expect(await screen.findByText(/import response was not confirmed/i)).toBeInTheDocument()
    fireEvent.click(importButton)
    fireEvent.click(screen.getByRole('button', { name: 'Import into target' }))
    await waitFor(() => expect(bundleApi.importIntoTarget).toHaveBeenCalledTimes(2))
    expect(vi.mocked(bundleApi.importIntoTarget).mock.calls[1]?.[2]).toBe('idem-key-1234')
    expect(randomUUID).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('This import was already processed; no changes were made.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open target project' })).toHaveAttribute('href', '/projects/target-uuid')
  })
})
