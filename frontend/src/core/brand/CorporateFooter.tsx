import { BRAND } from './brand'
import { InnovaLogo } from './BrandLogo'

interface CorporateFooterProps {
  className?: string
  compact?: boolean
  appearance?: 'auto' | 'light'
}

export function CorporateFooter({ className = '', compact = false, appearance = 'auto' }: CorporateFooterProps) {
  return (
    <footer className={`brand-corporate-footer${compact ? ' is-compact' : ''} ${className}`.trim()}>
      <InnovaLogo appearance={appearance} />
      <span>{BRAND.copyright}</span>
    </footer>
  )
}
