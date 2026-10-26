import { BRAND } from './brand'
import { InnovaLogo } from './BrandLogo'

interface CorporateFooterProps {
  className?: string
  compact?: boolean
}

export function CorporateFooter({ className = '', compact = false }: CorporateFooterProps) {
  return (
    <footer className={`brand-corporate-footer${compact ? ' is-compact' : ''} ${className}`.trim()}>
      <InnovaLogo />
      <span>{BRAND.copyright}</span>
    </footer>
  )
}
