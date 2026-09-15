import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { ProjectsPage } from './ProjectsPage'

const projectApi = vi.hoisted(() => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
}))

vi.mock('./projectsApi', () => ({
  listProjects: projectApi.listProjects,
  createProject: projectApi.createProject,
}))

const firstProject = {
  uuid: '11111111-1111-4111-8111-111111111111',
  code: 'SKY_GPU',
  status: 'AKTIF',
  name: 'SKY GPU Transfer',
  description: 'Oracle transfer project',
  version: 1,
  createdAt: '2026-09-12T00:00:00Z',
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/project/select']}>
      <Routes>
        <Route path="/project/select" element={<ProjectsPage />} />
        <Route path="/project" element={<h1>Selected project</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProjectsPage', () => {
  beforeEach(async () => {
    localStorage.clear()
    projectApi.listProjects.mockReset()
    projectApi.createProject.mockReset()
    await i18n.changeLanguage('en')
  })

  it('automatically opens the only available project', async () => {
    projectApi.listProjects.mockResolvedValue([firstProject])
    renderPage()

    expect(await screen.findByText('Selected project', { selector: 'h1' })).toBeInTheDocument()
  })

  it('requires an explicit choice when more than one project is available', async () => {
    projectApi.listProjects.mockResolvedValue([firstProject, { ...firstProject, uuid: '22222222-2222-4222-8222-222222222222', code: 'FINANCE', name: 'Finance' }])
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Select A Project' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /SKY GPU Transfer/ }))
    expect(await screen.findByRole('heading', { name: 'Selected project' })).toBeInTheDocument()
  })

  it('returns to a remembered project only while it is still accessible', async () => {
    const secondProject = { ...firstProject, uuid: '22222222-2222-4222-8222-222222222222', code: 'FINANCE', name: 'Finance' }
    localStorage.setItem('akis.lastProjectUuid', secondProject.uuid)
    projectApi.listProjects.mockResolvedValue([firstProject, secondProject])
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Selected project' })).toBeInTheDocument()
  })

  it('asks again when the remembered project is no longer accessible', async () => {
    localStorage.setItem('akis.lastProjectUuid', 'removed-project')
    projectApi.listProjects.mockResolvedValue([firstProject, { ...firstProject, uuid: '22222222-2222-4222-8222-222222222222', code: 'FINANCE', name: 'Finance' }])
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Select A Project' })).toBeInTheDocument()
  })

  it('opens the created project immediately after successful creation', async () => {
    projectApi.listProjects.mockResolvedValue([])
    projectApi.createProject.mockResolvedValue(firstProject)
    renderPage()

    await screen.findByText('No visible projects yet.')
    fireEvent.click(screen.getAllByRole('button', { name: 'New Project' })[0]!)
    fireEvent.change(screen.getByLabelText('Project code'), { target: { value: 'sky_gpu' } })
    fireEvent.change(screen.getByLabelText('Project name'), { target: { value: 'SKY GPU Transfer' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create Project' }))

    expect(projectApi.createProject).toHaveBeenCalledWith({
      code: 'SKY_GPU',
      name: 'SKY GPU Transfer',
      description: undefined,
    })
    expect(await screen.findByRole('heading', { name: 'Selected project' })).toBeInTheDocument()
  })
})
