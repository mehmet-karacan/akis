import { LoaderCircle } from 'lucide-react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonTone = 'primary' | 'secondary' | 'danger' | 'ghost'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  tone?: ButtonTone
  busy?: boolean
  busyLabel?: string
  icon?: ReactNode
}

export function Button({ tone = 'secondary', busy = false, busyLabel, icon, children, className = '', disabled, ...props }: ButtonProps) {
  return (
    <button
      {...props}
      className={`button ${tone} ${className}`.trim()}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
    >
      {busy ? <LoaderCircle className="ui-spin" size={16} aria-hidden="true" /> : icon}
      {busy && busyLabel ? busyLabel : children}
    </button>
  )
}
