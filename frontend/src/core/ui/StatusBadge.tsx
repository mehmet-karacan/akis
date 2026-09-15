import type { PropsWithChildren } from 'react'
import { Tag } from 'antd'

export type StatusTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

export function StatusBadge({ tone = 'neutral', children, className = '' }: PropsWithChildren<{ tone?: StatusTone; className?: string }>) {
  return <Tag color={tone === 'danger' ? 'error' : tone === 'info' ? 'processing' : tone === 'neutral' ? 'default' : tone} className={`akis-status ${tone} ${className}`.trim()}>{children}</Tag>
}
