import { FolderKanban, History, Info, Network } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink, useLocation } from 'react-router-dom'
import { projectRoute } from '../features/projects/CurrentProjectContext'

export type WorkspaceId = 'project' | 'development' | 'operations' | 'connections'

const workspaces = [
  { id: 'project', path: '', key: 'nav.projectTab', icon: Info },
  { id: 'development', path: '/objects', key: 'nav.development', icon: FolderKanban },
  { id: 'operations', path: '/operations', key: 'nav.operations', icon: History },
  { id: 'connections', path: '/connections', key: 'nav.connections', icon: Network },
] as const

export function resolveWorkspace(pathname: string): WorkspaceId {
  if (pathname.includes('/operations') || pathname.includes('/runs') || pathname.includes('/publications')) return 'operations'
  if (pathname.includes('/connections') || pathname.includes('/topology') || pathname.includes('/logical-schemas') || pathname.includes('/environments') || pathname.includes('/schema-bindings')) return 'connections'
  if (pathname.includes('/objects') || pathname.includes('/development') || pathname.includes('/definitions') || pathname.includes('/models')) return 'development'
  return 'project'
}

export function WorkspaceNavigation({ hasPendingChanges, onNavigate }: {
  hasPendingChanges: boolean
  onNavigate: (path: string) => void
}) {
  const { t } = useTranslation()
  const location = useLocation()
  return (
    <div className="workspace-navigation" role="tablist" aria-label={t('nav.workspaces')}>
      {workspaces.map(({ id, path, key, icon: Icon }) => {
        const target = projectRoute(path)
        return <NavLink
          key={key}
          aria-label={t(key)}
          role="tab"
          aria-selected={resolveWorkspace(location.pathname) === id}
          to={target}
          onClick={(event) => { if (hasPendingChanges) { event.preventDefault(); onNavigate(target) } }}
          end={id === 'project'}
          className={`workspace-tab ${resolveWorkspace(location.pathname) === id ? 'active' : ''}`}
        ><Icon size={18} /><span>{t(key)}</span></NavLink>
      })}
    </div>
  )
}
