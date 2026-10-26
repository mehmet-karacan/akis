import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { AntDesignProvider } from '../../core/theme/AntDesignProvider'
import { ThemeProvider } from '../../core/theme/ThemeContext'
import i18n from '../../core/i18n'
import { LoginPage } from './LoginPage'

vi.mock('../../core/auth/AuthContext', () => ({
  useAuth: () => ({ login: vi.fn(), setupPassword: vi.fn() }),
}))

it('presents the corporate Data Integrator Tool sign-in surface', () => {
  render(
    <MemoryRouter>
      <ThemeProvider>
        <AntDesignProvider>
          <LoginPage />
        </AntDesignProvider>
      </ThemeProvider>
    </MemoryRouter>,
  )

  expect(screen.getByText('Data Integrator Tool')).toBeInTheDocument()
  expect(screen.getByText('CORPORATE ACCESS')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
})

it('keeps the corporate login wording localized in Turkish', async () => {
  await i18n.changeLanguage('tr')

  render(
    <MemoryRouter>
      <ThemeProvider>
        <AntDesignProvider>
          <LoginPage />
        </AntDesignProvider>
      </ThemeProvider>
    </MemoryRouter>,
  )

  expect(screen.getByText('Veri Entegrasyon Aracı')).toBeInTheDocument()
  expect(screen.getByText('KURUMSAL ERİŞİM')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Giriş yap' })).toBeInTheDocument()

  await i18n.changeLanguage('en')
})
