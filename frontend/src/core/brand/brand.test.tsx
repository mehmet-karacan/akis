import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { BrandLogo } from './BrandLogo'
import { CorporateFooter } from './CorporateFooter'
import { ProductLockup } from './ProductLockup'

it('uses the shared Data Integrator Tool name for each product placement', () => {
  const { container } = render(
    <>
      <BrandLogo variant="login" />
      <BrandLogo variant="topbar" />
      <BrandLogo variant="compact" />
    </>,
  )

  const logos = [...container.querySelectorAll<HTMLImageElement>('img')]
  expect(logos).toHaveLength(3)
  expect(logos.every((logo) => logo.alt === 'Data Integrator Tool')).toBe(true)
  expect(new Set(logos.map((logo) => logo.className)).size).toBe(3)
})

it('renders the corporate lockup with the Innova mark followed by the product name', () => {
  render(<ProductLockup appearance="light" />)

  expect(screen.getByRole('img', { name: 'İnnova Bilişim Çözümleri A.Ş.' })).toBeInTheDocument()
  expect(screen.getByText('Data Integrator Tool')).toBeInTheDocument()
})

it('keeps the corporate footer wording and corporate mark stable', () => {
  render(<CorporateFooter />)

  expect(screen.getByText('2025 - 2026 İnnova Bilişim Çözümleri A.Ş.')).toBeInTheDocument()
  expect(screen.getByRole('img', { name: 'İnnova Bilişim Çözümleri A.Ş.' })).toBeInTheDocument()
})

it('supports a light corporate mark when the footer sits on a dark surface', () => {
  render(<CorporateFooter appearance="light" />)

  expect(screen.getByRole('img', { name: 'İnnova Bilişim Çözümleri A.Ş.' })).toHaveClass('is-light')
})
