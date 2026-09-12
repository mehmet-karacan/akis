import { Cable, Database, GitMerge, Globe2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'

const links = [
  { path: '/connections', key: 'nav.connectionCatalog', icon: Cable },
  { path: '/logical-schemas', key: 'nav.logicalSchemas', icon: Database },
  { path: '/environments', key: 'nav.environments', icon: Globe2 },
  { path: '/schema-bindings', key: 'nav.schemaBindings', icon: GitMerge },
] as const

export function ConnectionsSubnavigation({ projectUuid, hasPendingChanges, onNavigate }: { projectUuid: string; hasPendingChanges: boolean; onNavigate: (path: string) => void }) {
  const { t } = useTranslation()
  return <div className="workspace-subnavigation workspace-link-list"><p className="nav-label">{t('nav.connectionsContext')}</p>{links.map(({ path, key, icon: Icon }) => {
    const target = `/projects/${projectUuid}${path}`
    return <NavLink key={key} end={path === '/connections'} className={({ isActive }) => `workspace-subnav-link ${isActive ? 'active' : ''}`} to={target} onClick={(event) => { if (hasPendingChanges) { event.preventDefault(); onNavigate(target) } }}><Icon /><span>{t(key)}</span></NavLink>
  })}</div>
}
