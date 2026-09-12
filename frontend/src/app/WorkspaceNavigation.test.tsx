import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../core/i18n'
import { resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'

describe('WorkspaceNavigation', () => {
  beforeEach(async () => { await i18n.changeLanguage('en') })

  it('always exposes the four primary workspaces', () => {
    render(<MemoryRouter initialEntries={['/project/objects']}><WorkspaceNavigation hasPendingChanges={false} onNavigate={() => undefined} /></MemoryRouter>)
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Project', 'Project objects', 'Operations', 'Connections'])
  })

  it('maps legacy routes into the canonical workspace', () => {
    expect(resolveWorkspace('/projects/p-1/topology')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/schema-bindings')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/runs')).toBe('operations')
    expect(resolveWorkspace('/projects/p-1/models')).toBe('development')
    expect(resolveWorkspace('/projects/p-1')).toBe('project')
  })

  it('keeps project information as the first tab for every role', () => {
    render(<MemoryRouter><WorkspaceNavigation hasPendingChanges={false} onNavigate={() => undefined} /></MemoryRouter>)
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Project', 'Project objects', 'Operations', 'Connections'])
  })
})
