import type { ReactNode } from 'react'
import { Card } from 'antd'

/** Shared header / filters / content contract for list and detail screens. */
export function WorkspaceSection({ title, icon, actions, filters, children }: { title: string; icon?: ReactNode; actions?: ReactNode; filters?: ReactNode; children: ReactNode }) {
  return <Card className="workspace-section" title={<span className="ui-inline-title">{icon}{title}</span>} extra={actions}><div className="workspace-section-content">{filters && <div className="workspace-section-filters">{filters}</div>}{children}</div></Card>
}
