import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  eyebrow?: string
  icon?: ReactNode
  actions?: ReactNode
  className?: string
}

export function PageHeader({ title, description, eyebrow, icon, actions, className = '' }: PageHeaderProps) {
  return (
    <header className={`ui-page-header ${className}`.trim()}>
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <div className="ui-page-title-row">{icon ? <span className="ui-page-title-icon" aria-hidden="true">{icon}</span> : null}<h1>{title}</h1></div>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="ui-page-actions">{actions}</div> : null}
    </header>
  )
}
