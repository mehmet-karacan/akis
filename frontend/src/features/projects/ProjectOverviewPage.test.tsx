import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { ProjectOverviewPage } from './ProjectOverviewPage'

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
    await i18n.changeLanguage('en')
  })

  it('renders a calm project entry without operational metrics or export actions', async () => {
    render(<MemoryRouter initialEntries={['/projects/project-1']}>
      <Routes><Route path="/projects/:projectUuid" element={<ProjectOverviewPage />} /></Routes>
    </MemoryRouter>)

    expect(await screen.findByRole('heading', { name: 'Akış' })).toBeInTheDocument()
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Project Objects/ })).toHaveAttribute('href', '/project/objects')
    expect(screen.getByRole('link', { name: /Connections/ })).toHaveAttribute('href', '/project/connections')
    expect(screen.getByRole('link', { name: /Run History/ })).toHaveAttribute('href', '/project/operations')
    expect(screen.getByRole('link', { name: /Models/ })).toHaveAttribute('href', '/project/models')
    expect(screen.queryByText('Recent activity')).not.toBeInTheDocument()
    expect(screen.queryByText('Export project')).not.toBeInTheDocument()
  })
})
