import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { BrandLogo } from './BrandLogo'
import { CorporateFooter } from './CorporateFooter'

it('uses the shared AKIŞ logo registry for each product placement', () => {
  const { container } = render(
    <>
      <BrandLogo variant="login" />
      <BrandLogo variant="topbar" />
      <BrandLogo variant="compact" />
    </>,
  )

  const logos = [...container.querySelectorAll<HTMLImageElement>('img')]
  expect(logos).toHaveLength(3)
  expect(logos.every((logo) => logo.alt === 'AKIŞ')).toBe(true)
  expect(new Set(logos.map((logo) => logo.className)).size).toBe(3)
})

it('keeps the corporate footer wording stable without repeating the header logo', () => {
  render(<CorporateFooter />)

  expect(screen.getByText('2025 - 2026 İnnova Bilişim Çözümleri A.Ş.')).toBeInTheDocument()
  expect(screen.queryByRole('img', { name: 'İnnova Bilişim Çözümleri A.Ş.' })).not.toBeInTheDocument()
})
