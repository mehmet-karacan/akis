import { Dropdown, type MenuProps } from 'antd'
import { Database, Layers3, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { DefinitionTypeIcon } from '../features/definitions/DefinitionTypeIcon'
import { DEFINITION_TYPES, type DefinitionType } from '../features/definitions/types'
import { useDocumentTabs } from './DocumentTabsContext'
import { destinations, resolveNavigationItem } from './WorkspaceNavigation'
import { projectRoute } from '../features/projects/CurrentProjectContext'
import './document-tabs.css'

function TabIcon({ kind }: { kind: string }) {
  if ((DEFINITION_TYPES as readonly string[]).includes(kind)) return <DefinitionTypeIcon type={kind as DefinitionType} size={12} />
  if (kind === 'MODEL') return <span className="definition-type-icon definition-type-icon--model" aria-hidden="true" style={{ width: 21, height: 21 }}><Layers3 size={12} /></span>
  return <span className="definition-type-icon" aria-hidden="true" style={{ width: 21, height: 21 }}><Database size={12} /></span>
}

/** Opened documents as tabs (editor tabs) plus the workbench maximize toggle; applies to every workspace. */
export function DocumentTabBar({ onNavigate, dirtyPath }: { onNavigate(path: string): void; dirtyPath?: string | null }) {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const { tabs, close, closeOthers } = useDocumentTabs()
  const destination = destinations.find(item => item.id === resolveNavigationItem(pathname)) ?? destinations[0]
  const SectionIcon = destination.icon
  const sectionPath = destination.absolute ? destination.path : projectRoute(destination.path)
  const documentActive = tabs.some(tab => tab.path === pathname)
  const closeTab = (path: string) => {
    const next = close(path)
    if (pathname === path) onNavigate(next ?? '/project/objects')
  }
  const menu = (path: string): MenuProps['items'] => [
    { key: 'close', label: t('tabs.close'), onClick: () => closeTab(path) },
    { key: 'others', label: t('tabs.closeOthers'), onClick: () => { closeOthers(path); if (pathname !== path) onNavigate(path) } },
  ]
  return <div className="document-tabbar" role="tablist" aria-label={t('tabs.openDocuments')}>
    <button type="button" className={`document-section-tab${documentActive ? '' : ' is-active'}`} role="tab" aria-selected={!documentActive} title={t(destination.key)} onClick={() => { if (pathname !== sectionPath) onNavigate(sectionPath) }}>
      <span className={`workspace-icon workspace-icon--${destination.id}`} aria-hidden="true"><SectionIcon size={15} /></span>
      <span className="document-tab-title">{t(destination.key)}</span>
    </button>
    {tabs.length > 0 && <span className="document-tab-divider" aria-hidden="true" />}
    <div className="document-tabs">
      {tabs.map((tab) => {
        const active = pathname === tab.path
        return <Dropdown key={tab.path} trigger={['contextMenu']} menu={{ items: menu(tab.path) }}>
          <div className={`document-tab${active ? ' is-active' : ''}`} role="tab" aria-selected={active} tabIndex={0} title={tab.subtitle ? `${tab.title} · ${tab.subtitle}` : tab.title}
            onClick={() => { if (!active) onNavigate(tab.path) }} onKeyDown={(event) => { if (event.key === 'Enter') onNavigate(tab.path) }} onAuxClick={(event) => { if (event.button === 1) closeTab(tab.path) }}>
            <TabIcon kind={tab.kind} />
            <span className="document-tab-title">{tab.title}</span>
            {dirtyPath === tab.path && <span className="document-tab-dirty" aria-label={t('tabs.unsaved')} />}
            <button type="button" className="document-tab-close" aria-label={`${t('tabs.close')}: ${tab.title}`} onClick={(event) => { event.stopPropagation(); closeTab(tab.path) }}><X size={12} /></button>
          </div>
        </Dropdown>
      })}
    </div>
  </div>
}
