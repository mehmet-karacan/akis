import type { ReactNode } from 'react'
import { Card } from 'antd'

export function CollectionCard({ icon, title, subtitle, children, actions }: { icon: ReactNode; title: ReactNode; subtitle?: ReactNode; children?: ReactNode; actions?: ReactNode }) {
  return <Card className="ui-collection-card" title={<span className="ui-inline-title">{icon}{title}</span>}><div className="ui-collection-card-body">{subtitle && <p className="ui-secondary">{subtitle}</p>}{children}</div>{actions && <footer className="ui-page-actions">{actions}</footer>}</Card>
}
