import { Code2, Gauge, Network } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'

export type WorkspaceId = 'development' | 'operations' | 'connections'

const workspaces = [
  { id: 'development', path: '/development', key: 'nav.development', icon: Code2 },
  { id: 'operations', path: '/operations', key: 'nav.operations', icon: Gauge },
  { id: 'connections', path: '/connections', key: 'nav.connections', icon: Network },
] as const

export function resolveWorkspace(pathname: string): WorkspaceId {
  if (pathname.includes('/operations') || pathname.includes('/runs') || pathname.includes('/publications')) return 'operations'
  if (pathname.includes('/connections') || pathname.includes('/topology') || pathname.includes('/logical-schemas') || pathname.includes('/environments') || pathname.includes('/schema-bindings')) return 'connections'
  return 'development'
}

export function WorkspaceNavigation({ projectUuid, collapsed, hasPendingChanges, onNavigate }: {
  projectUuid: string
  collapsed: boolean
  hasPendingChanges: boolean
  onNavigate: (path: string) => void
}) {
  const { t } = useTranslation()
  return (
    <div className="workspace-navigation">
      {!collapsed && <p className="nav-label">{t('nav.workspaces')}</p>}
      {workspaces.map(({ path, key, icon: Icon }) => {
        const target = `/projects/${projectUuid}${path}`
        return <NavLink
          key={key}
          title={collapsed ? t(key) : undefined}
          aria-label={t(key)}
          to={target}
          onClick={(event) => { if (hasPendingChanges) { event.preventDefault(); onNavigate(target) } }}
          className={({ isActive }) => `nav-item mobile-primary ${isActive ? 'active' : ''}`}
        ><Icon size={18} /><span>{t(key)}</span></NavLink>
      })}
    </div>
  )
}
