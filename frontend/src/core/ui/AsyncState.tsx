import { RefreshCw } from 'lucide-react'
import { Alert, Empty, Spin } from 'antd'
import type { ReactNode } from 'react'
import { Button } from './Button'

interface AsyncStateProps {
  state: 'loading' | 'error' | 'empty'
  title: ReactNode
  description?: string
  retryLabel?: string
  onRetry?: () => void
  action?: ReactNode
  compact?: boolean
  className?: string
}

export function AsyncState({ state, title, description, retryLabel, onRetry, action, compact = false, className = '' }: AsyncStateProps) {
  return (
    <div
      className={`ui-async-state ${state} ${compact ? 'compact' : ''} ${className}`.trim()}
      role={state === 'error' ? undefined : 'status'}
      aria-live={state === 'loading' ? 'polite' : undefined}
    >
      {state === 'loading' ? <><Spin /><span>{title}</span></> : state === 'error' ? <Alert type="error" showIcon title={title} description={description} action={onRetry && retryLabel ? <Button type="button" icon={<RefreshCw size={16} />} onClick={onRetry}>{retryLabel}</Button> : action} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<><strong>{title}</strong>{description && <p>{description}</p>}</>}>{action}</Empty>}
    </div>
  )
}
