import { InnovaLogo } from './BrandLogo'
import { useTranslation } from 'react-i18next'

interface ProductLockupProps {
  className?: string
  appearance?: 'auto' | 'light'
}

export function ProductLockup({ className = '', appearance = 'auto' }: ProductLockupProps) {
  const { t } = useTranslation()

  return (
    <div className={`product-lockup${appearance === 'light' ? ' is-light' : ''} ${className}`.trim()}>
      <InnovaLogo appearance={appearance} />
      <span className="product-lockup-divider" aria-hidden="true" />
      <span className="product-lockup-name">{t('brand.productName')}</span>
    </div>
  )
}
