import type { PropsWithChildren } from 'react'

export type StatusTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

export function StatusBadge({ tone = 'neutral', children, className = '' }: PropsWithChildren<{ tone?: StatusTone; className?: string }>) {
  return <span className={`ui-status-badge ${tone} ${className}`.trim()}>{children}</span>
}
