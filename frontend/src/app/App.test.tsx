import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { AuthProvider } from '../core/auth/AuthContext'
import { ThemeProvider } from '../core/theme/ThemeContext'
import { App } from './App'

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <ThemeProvider><AuthProvider><App /></AuthProvider></ThemeProvider>
    </MemoryRouter>,
  )
}

describe('application foundation', () => {
  beforeEach(async () => {
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('starts in English and exposes the secure development sign-in', () => {
    renderApp()
    expect(screen.getByRole('heading', { name: 'Build trusted data flows.' })).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveValue('developer')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })

  it('switches the sign-in experience to Turkish', async () => {
    renderApp()
    fireEvent.click(screen.getByRole('button', { name: /TR/ }))
    expect(await screen.findByRole('heading', { name: 'Güvenilir veri akışları tasarlayın.' })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('tr')
  })

  it('always lands on mandatory project selection after sign-in', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([]), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))
    renderApp()

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'local-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Select A Project' })).toBeInTheDocument()
    expect(await screen.findByText('No visible projects yet.')).toBeInTheDocument()
  })
})
