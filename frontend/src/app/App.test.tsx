import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
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
    await i18n.changeLanguage('en')
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
})
