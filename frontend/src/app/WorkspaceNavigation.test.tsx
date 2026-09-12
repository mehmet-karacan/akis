import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../core/i18n'
import { resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'

describe('WorkspaceNavigation', () => {
  beforeEach(async () => { await i18n.changeLanguage('en') })

  it('always exposes the three primary workspaces', () => {
    render(<MemoryRouter initialEntries={['/projects/p-1/development']}><WorkspaceNavigation projectUuid="p-1" collapsed={false} hasPendingChanges={false} onNavigate={() => undefined} /></MemoryRouter>)
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Project', 'Operations', 'Connections'])
  })

  it('maps legacy routes into the canonical workspace', () => {
    expect(resolveWorkspace('/projects/p-1/topology')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/schema-bindings')).toBe('connections')
    expect(resolveWorkspace('/projects/p-1/runs')).toBe('operations')
    expect(resolveWorkspace('/projects/p-1/models')).toBe('development')
  })

  it('keeps all workspaces available but puts operations first for an operator-only role', () => {
    render(<MemoryRouter><WorkspaceNavigation projectUuid="p-1" collapsed={false} hasPendingChanges={false} operatorOnly onNavigate={() => undefined} /></MemoryRouter>)
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Operations', 'Project', 'Connections'])
  })
})
