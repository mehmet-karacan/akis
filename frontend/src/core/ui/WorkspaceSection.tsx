import type { ReactNode } from 'react'

/** Shared header / filters / content contract for list and detail screens. */
export function WorkspaceSection({ title, icon, actions, filters, children }: { title: string; icon?: ReactNode; actions?: ReactNode; filters?: ReactNode; children: ReactNode }) {
  return <section className="workspace-section"><header><h2>{icon}{title}</h2>{actions && <div className="ui-page-actions">{actions}</div>}</header>{filters && <div className="workspace-section-filters">{filters}</div>}<div className="workspace-section-content">{children}</div></section>
}
