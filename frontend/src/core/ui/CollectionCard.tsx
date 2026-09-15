import type { ReactNode } from 'react'

export function CollectionCard({ icon, title, subtitle, children, actions }: { icon: ReactNode; title: ReactNode; subtitle?: ReactNode; children?: ReactNode; actions?: ReactNode }) {
  return <article className="ui-collection-card"><header><span className="ui-collection-icon">{icon}</span><div>{title}{subtitle && <small>{subtitle}</small>}</div></header><div className="ui-collection-card-body">{children}</div>{actions && <footer>{actions}</footer>}</article>
}
