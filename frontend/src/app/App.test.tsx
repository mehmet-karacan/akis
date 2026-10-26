import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

  it('starts in English and exposes the secure development sign-in', async () => {
    renderApp()
    expect(await screen.findByRole('heading', { name: 'Build trusted data flows.' })).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveValue('')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })

  it('switches the sign-in experience to Turkish', async () => {
    renderApp()
    fireEvent.click(await screen.findByRole('button', { name: /Language/ }))
    fireEvent.click(await screen.findByText('Turkish'))
    expect(await screen.findByRole('heading', { name: 'Güvenilir veri akışları tasarlayın.' })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('tr')
  })

  it('submits local credentials without a prefilled username', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      if (url.endsWith('/api/v1/auth/me')) {
        return Promise.resolve(new Response('null', { status: 200, headers: { 'Content-Type': 'application/json' } }))
      }
      if (url.endsWith('/api/v1/auth/csrf')) {
        return Promise.resolve(new Response(JSON.stringify({ token: 'test-csrf-token', headerName: 'X-XSRF-TOKEN' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      if (url.endsWith('/api/v1/auth/login')) {
        return Promise.resolve(new Response(JSON.stringify({
          id: 1,
          uuid: 'user-uuid',
          kullaniciKodu: 'developer',
          gorunenAd: 'Developer',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      }
      if (url.endsWith('/api/v1/projects')) {
        return Promise.resolve(new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }))
      }
      return Promise.resolve(new Response('', { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp()

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'developer' } })
    fireEvent.change(await screen.findByLabelText('Password'), { target: { value: 'local-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/v1/auth/login'))).toBe(true))
  })
})
