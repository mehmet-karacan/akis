import { Database, FolderKanban, History, Info, Network } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { Menu } from 'antd'
import { projectRoute } from '../features/projects/CurrentProjectContext'

export type WorkspaceId = 'project' | 'development' | 'operations' | 'connections' | 'schema-metadata'

const workspaces = [
  { id: 'project', path: '', key: 'nav.projectTab', icon: Info, absolute: false },
  { id: 'development', path: '/objects', key: 'nav.development', icon: FolderKanban, absolute: false },
  { id: 'operations', path: '/operations', key: 'nav.operations', icon: History, absolute: false },
  { id: 'connections', path: '/connections', key: 'nav.connections', icon: Network, absolute: false },
  { id: 'schema-metadata', path: '/schema-metadata', key: 'nav.schemaMetadata', icon: Database, absolute: true },
] as const

export function resolveWorkspace(pathname: string): WorkspaceId {
  if (pathname.includes('/schema-metadata')) return 'schema-metadata'
  if (pathname.includes('/operations') || pathname.includes('/runs') || pathname.includes('/publications') || pathname.includes('/schedules')) return 'operations'
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
  void hasPendingChanges // The shell guards every onNavigate request.
  return <Menu mode="inline" className="workspace-navigation" aria-label={t('nav.workspaces')}
    selectedKeys={[resolveWorkspace(location.pathname)]}
    onClick={({ key }) => {
      const workspace = workspaces.find(item => item.id === key)!
      onNavigate(workspace.absolute ? workspace.path : projectRoute(workspace.path))
    }}
    items={workspaces.map(({ id, key, icon: Icon }) => ({ key: id, className: `workspace-nav--${id}`, label: t(key), icon: <Icon size={16} /> }))} />
}
