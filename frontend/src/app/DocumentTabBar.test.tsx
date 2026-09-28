import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { DocumentTabsProvider } from './DocumentTabsContext'
import { DocumentTabBar } from './DocumentTabBar'

describe('DocumentTabBar', () => {
  beforeEach(async () => { sessionStorage.clear(); await i18n.changeLanguage('tr') })

  it('shows the current catalog as the active tab when no document is open', () => {
    render(<MemoryRouter initialEntries={['/project/objects']}><DocumentTabsProvider projectUuid="test"><DocumentTabBar onNavigate={() => undefined} /></DocumentTabsProvider></MemoryRouter>)
    expect(screen.getByRole('tab', { name: 'Akış Tasarımı' })).toHaveAttribute('aria-selected', 'true')
  })

  it('keeps section naming aligned with run history and navigates to its root', () => {
    const onNavigate = vi.fn()
    render(<MemoryRouter initialEntries={['/project/operations']}><DocumentTabsProvider projectUuid="test"><DocumentTabBar onNavigate={onNavigate} /></DocumentTabsProvider></MemoryRouter>)
    const tab = screen.getByRole('tab', { name: 'Çalıştırma Geçmişi' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(tab)
    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('marks the open document active while retaining its parent section', () => {
    sessionStorage.setItem('akis.documentTabs:test', JSON.stringify([{ path: '/project/objects/definitions/example', title: 'Örnek Akış', kind: 'MAPPING' }]))
    render(<MemoryRouter initialEntries={['/project/objects/definitions/example']}><DocumentTabsProvider projectUuid="test"><DocumentTabBar onNavigate={() => undefined} /></DocumentTabsProvider></MemoryRouter>)
    expect(screen.getByRole('tab', { name: 'Akış Tasarımı' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tab', { name: /Örnek Akış/ })).toHaveAttribute('aria-selected', 'true')
  })
})
