import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { definitionsApi } from '../definitions/api'
import { ProjectOverviewPage } from './ProjectOverviewPage'

vi.mock('../../core/ui/useRecordAudit', () => ({ useRecordAudit: () => ({
  state: 'ready', records: { 'recent-1': { uuid: 'recent-1', createdBy: 'Mehmet Karacan', createdAt: '2026-09-24T10:30:00Z', updatedBy: 'Developer', updatedAt: '2026-09-25T11:30:00Z' } },
}) }))

vi.mock('./projectsApi', () => ({
  getProject: vi.fn().mockResolvedValue({
    uuid: 'project-1',
    code: 'AKIS',
    name: 'Akış',
    description: 'Integration project',
    status: 'AKTIF',
    version: 1,
  }),
}))

describe('ProjectOverviewPage', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    await i18n.changeLanguage('en')
    vi.spyOn(definitionsApi, 'listRecentDefinitions').mockResolvedValue([{ uuid: 'recent-1', folderUuid: null, type: 'PROCEDURE', code: 'CLEANUP', status: 'AKTIF', name: 'Cleanup', description: null, version: 1 }])
  })

  it('renders a calm project entry without operational metrics or export actions', async () => {
    render(<MemoryRouter initialEntries={['/projects/project-1']}>
      <Routes><Route path="/projects/:projectUuid" element={<ProjectOverviewPage />} /></Routes>
    </MemoryRouter>)

    expect(await screen.findByRole('heading', { name: 'Akış' })).toBeInTheDocument()
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Flow Design/ })[0]).toHaveAttribute('href', '/project/objects')
    expect(screen.getByRole('link', { name: /Connections/ })).toHaveAttribute('href', '/project/connections')
    expect(screen.getByRole('link', { name: /Run History/ })).toHaveAttribute('href', '/project/operations')
    expect(screen.getByRole('link', { name: /Models/ })).toHaveAttribute('href', '/project/models')
    expect(await screen.findByRole('heading', { name: '5 Most Recently Defined Objects' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: '5 most recently defined objects table' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Created By/ })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Created At/ })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Mehmet Karacan' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Developer' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cleanup' })).toHaveAttribute('href', '/project/objects/definitions/recent-1')
    expect(screen.queryByText('Recent activity')).not.toBeInTheDocument()
    expect(screen.queryByText('Export project')).not.toBeInTheDocument()
  })
})
