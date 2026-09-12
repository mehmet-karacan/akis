import type { PropsWithChildren, ReactNode } from 'react'

export function FilterBar({ children, actions, className = '' }: PropsWithChildren<{ actions?: ReactNode; className?: string }>) {
  return <section className={`ui-filter-bar ${className}`.trim()}>{children}{actions ? <div className="ui-filter-actions">{actions}</div> : null}</section>
}
