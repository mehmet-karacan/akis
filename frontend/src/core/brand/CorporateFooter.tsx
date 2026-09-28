import { BRAND } from './brand'

interface CorporateFooterProps {
  className?: string
  compact?: boolean
}

export function CorporateFooter({ className = '', compact = false }: CorporateFooterProps) {
  return (
    <footer className={`brand-corporate-footer${compact ? ' is-compact' : ''} ${className}`.trim()}>
      <span>{BRAND.copyright}</span>
    </footer>
  )
}
