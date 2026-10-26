import { render, screen } from '@testing-library/react'
import { beforeEach, expect, it } from 'vitest'
import i18n from '../core/i18n'
import { TopbarBrand } from './AppShell'

beforeEach(async () => {
  await i18n.changeLanguage('en')
})

it('renders the post-login brand as Innova followed by Data Integrator Tool', () => {
  const { container } = render(<TopbarBrand compact={false} />)
  const lockup = container.querySelector('.topbar-product-lockup')

  expect(lockup).toHaveClass('product-lockup')
  expect(lockup?.firstElementChild).toHaveClass('brand-innova-logo')
  expect(screen.getByText('Data Integrator Tool')).toBeInTheDocument()
  expect(container.querySelector('img[alt="Data Integrator Tool"]')).not.toBeInTheDocument()
})

it('follows the Turkish locale for the product name without changing brand order', async () => {
  await i18n.changeLanguage('tr')
  const { container } = render(<TopbarBrand compact={false} />)
  const lockup = container.querySelector('.topbar-product-lockup')

  expect(lockup?.firstElementChild).toHaveClass('brand-innova-logo')
  expect(screen.getByText('Veri Entegrasyon Aracı')).toBeInTheDocument()
  expect(screen.queryByText('Data Integrator Tool')).not.toBeInTheDocument()
})
