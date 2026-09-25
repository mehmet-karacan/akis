import { Button as AntButton } from 'antd'
import { LoaderCircle } from 'lucide-react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonTone = 'primary' | 'secondary' | 'danger' | 'ghost'

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'color'> {
  tone?: ButtonTone
  busy?: boolean
  busyLabel?: string
  icon?: ReactNode
}

export function Button({ tone = 'secondary', busy = false, busyLabel, icon, children, className = '', disabled, type = 'button', ...props }: ButtonProps) {
  const iconOnly = Boolean(icon && children == null && !busyLabel)
  return (
    <AntButton
      {...props}
      className={`akis-button${iconOnly ? ' akis-button--icon-only' : ''} ${className}`.trim()}
      data-icon-only={iconOnly || undefined}
      data-tone={tone}
      type={tone === 'primary' || tone === 'danger' ? 'primary' : tone === 'ghost' ? 'text' : 'default'}
      danger={tone === 'danger'}
      htmlType={type}
      icon={busy ? <LoaderCircle className="ui-spin" size={16} aria-hidden="true" /> : icon}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
    >
      {busy && busyLabel ? busyLabel : children}
    </AntButton>
  )
}
