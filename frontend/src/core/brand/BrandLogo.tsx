import akisCompact from '../../assets/brand/akis-compact@2x.png'
import akisIcon from '../../assets/brand/akis-icon-72.png'
import akisLoginCompact from '../../assets/brand/akis-login-compact@2x.png'
import akisLogin from '../../assets/brand/akis-login@2x.png'
import akisTopbar from '../../assets/brand/akis-topbar@2x.png'
import innovaLogo from '../../assets/brand/innova-logo.svg'
import innovaLogoWhite from '../../assets/brand/innova-logo-white.svg'
import { BRAND, type ProductLogoVariant } from './brand'

const PRODUCT_LOGOS: Record<ProductLogoVariant, string> = {
  login: akisLogin,
  loginCompact: akisLoginCompact,
  topbar: akisTopbar,
  compact: akisCompact,
  icon: akisIcon,
}

interface BrandLogoProps {
  className?: string
  variant?: ProductLogoVariant
}

export function BrandLogo({ className = '', variant = 'topbar' }: BrandLogoProps) {
  return (
    <img
      className={`brand-product-logo brand-product-logo--${variant} ${className}`.trim()}
      src={PRODUCT_LOGOS[variant]}
      alt={BRAND.productName}
    />
  )
}

interface InnovaLogoProps {
  className?: string
}

export function InnovaLogo({ className = '' }: InnovaLogoProps) {
  return (
    <span className={`brand-innova-logo ${className}`.trim()} aria-label={BRAND.companyName} role="img">
      <img className="brand-innova-logo--light" src={innovaLogo} alt="" aria-hidden="true" />
      <img className="brand-innova-logo--dark" src={innovaLogoWhite} alt="" aria-hidden="true" />
    </span>
  )
}
