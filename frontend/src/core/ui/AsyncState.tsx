import { Inbox, LoaderCircle, RefreshCw, TriangleAlert } from 'lucide-react'
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
  const Icon = state === 'loading' ? LoaderCircle : state === 'error' ? TriangleAlert : Inbox
  return (
    <div
      className={`ui-async-state ${state} ${compact ? 'compact' : ''} ${className}`.trim()}
      role={state === 'error' ? 'alert' : 'status'}
      aria-live={state === 'loading' ? 'polite' : undefined}
    >
      <Icon className={state === 'loading' ? 'ui-spin' : ''} aria-hidden="true" />
      <div><strong>{title}</strong>{description ? <p>{description}</p> : null}</div>
      {state === 'error' && onRetry && retryLabel ? <Button type="button" icon={<RefreshCw size={16} />} onClick={onRetry}>{retryLabel}</Button> : action}
    </div>
  )
}
