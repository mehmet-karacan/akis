import { Button as AntActionButton } from '../core/ui/Button'
import { LanguageSwitcher } from '../core/ui/LanguageSwitcher'
import { ThemeSwitcher } from '../core/ui/ThemeSwitcher'
import {
  CircleUserRound, DatabaseZap, LogOut, PanelLeft, PanelLeftClose, PanelLeftOpen,
} from 'lucide-react'
import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { Grid } from 'antd'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { apiRequest } from '../core/api/client'
import { useAuth } from '../core/auth/AuthContext'
import { ProjectAccessProvider, type ProjectAccess } from '../core/auth/ProjectAccessContext'
import { PendingChangesContext, type PendingChanges } from '../core/navigation/PendingChangesContext'
import { Dialog } from '../core/ui/Dialog'
import type { Project } from '../features/projects/projectsApi'
import { CurrentProjectProvider } from '../features/projects/CurrentProjectContext'
import { getRememberedProject } from '../features/projects/projectPreference'
import { ProjectSwitcher } from './ProjectSwitcher'
import { ConnectionsSubnavigation } from './ConnectionsSubnavigation'
import { OperationsSubnavigation } from './OperationsSubnavigation'
import { resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'
import { DesignWorkspace } from './DesignWorkspace'
import { clampExplorerWidth, ExplorerResizeHandle } from './ExplorerResizeHandle'
import { DocumentTabsProvider, useDocumentTabs } from './DocumentTabsContext'
import { DocumentTabBar } from './DocumentTabBar'

export function AppShell() {
  const { username, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const compact = screens.lg === false
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false)
  const [navigationCollapsed, setNavigationCollapsed] = useState(() => {
    try { return localStorage.getItem('akis.navigation.collapsed.v1') === 'true' } catch { return false }
  })
  useEffect(() => {
    try { localStorage.setItem('akis.navigation.collapsed.v1', String(navigationCollapsed)) } catch { /* Storage is optional. */ }
  }, [navigationCollapsed])
  const [explorerWidth, setExplorerWidth] = useState(() => {
    try { return clampExplorerWidth(Number(localStorage.getItem('akis.explorer.width.v2')) || 240, window.innerWidth) } catch { return 240 }
  })
  useEffect(() => { try { localStorage.setItem('akis.explorer.width.v2', String(explorerWidth)) } catch { /* Storage is optional. */ } }, [explorerWidth])
  useEffect(() => {
    const resize = () => setExplorerWidth(width => clampExplorerWidth(width, window.innerWidth))
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])
  const [projectUuid, setProjectUuid] = useState(() => getRememberedProject() ?? '')
  const [project, setProject] = useState<Project | null>(null)
  const [projectAccess, setProjectAccess] = useState<ProjectAccess | null>(null)
  const [pendingChanges, setPendingChangesState] = useState<PendingChanges | null>(null)
  const [pendingPath, setPendingPath] = useState<string | null>(null)
  const [savingBeforeLeave, setSavingBeforeLeave] = useState(false)
  const completeNavigation = useCallback((path: string) => {
    if (path === '/login') logout()
    navigate(path)
  }, [logout, navigate])

  useEffect(() => {
    if (!projectUuid) {
      setProject(null)
      setProjectAccess(null)
      return
    }
    setProject(null)
    setProjectAccess(null)
    let active = true
    void apiRequest<Project>(`/api/v1/projects/${projectUuid}`)
      .then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setProject(null) })
    void apiRequest<ProjectAccess>(`/api/v1/projects/${projectUuid}/access`)
      .then((value) => { if (active) setProjectAccess(value) })
      .catch(() => { if (active) setProjectAccess(null) })
    return () => { active = false }
  }, [projectUuid])

  const setPendingChanges = useCallback((value: PendingChanges | null) => setPendingChangesState(value), [])
  const requestNavigation = useCallback((path: string) => {
    setMobileNavigationOpen(false)
    if (pendingChanges) setPendingPath(path)
    else completeNavigation(path)
  }, [completeNavigation, pendingChanges])

  useEffect(() => {
    const selectProject = (event: Event) => setProjectUuid((event as CustomEvent<string>).detail)
    window.addEventListener('akis:project-changed', selectProject)
    return () => window.removeEventListener('akis:project-changed', selectProject)
  }, [])

  const activeWorkspace = resolveWorkspace(location.pathname)

  useEffect(() => {
    window.scrollTo({ top: 0, left: 0 })
  }, [location.pathname])

  useEffect(() => {
    if (!pendingChanges) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pendingChanges])

  if (!projectUuid) return <Navigate to="/project/select" replace />

  return (
    <CurrentProjectProvider projectUuid={projectUuid}><ProjectAccessProvider value={projectAccess}><PendingChangesContext.Provider value={{ pendingChanges, setPendingChanges }}><DocumentTabsProvider projectUuid={projectUuid}><ShellBody compact={compact} mobileNavigationOpen={mobileNavigationOpen} setMobileNavigationOpen={setMobileNavigationOpen} navigationCollapsed={navigationCollapsed} setNavigationCollapsed={setNavigationCollapsed} explorerWidth={explorerWidth} setExplorerWidth={setExplorerWidth} project={project} projectUuid={projectUuid} username={username} pendingChanges={pendingChanges} requestNavigation={requestNavigation} activeWorkspace={activeWorkspace} pendingPath={pendingPath} setPendingPath={setPendingPath} savingBeforeLeave={savingBeforeLeave} setSavingBeforeLeave={setSavingBeforeLeave} completeNavigation={completeNavigation} setPendingChangesState={setPendingChangesState} /></DocumentTabsProvider></PendingChangesContext.Provider></ProjectAccessProvider></CurrentProjectProvider>
  )
}

interface ShellBodyProps {
  compact: boolean; mobileNavigationOpen: boolean; setMobileNavigationOpen(value: boolean | ((current: boolean) => boolean)): void
  navigationCollapsed: boolean; setNavigationCollapsed(value: boolean | ((current: boolean) => boolean)): void
  explorerWidth: number; setExplorerWidth(value: number): void; project: Project | null; projectUuid: string; username: string | null | undefined
  pendingChanges: PendingChanges | null; requestNavigation(path: string): void; activeWorkspace: string
  pendingPath: string | null; setPendingPath(value: string | null): void; savingBeforeLeave: boolean; setSavingBeforeLeave(value: boolean): void
  completeNavigation(path: string): void; setPendingChangesState(value: PendingChanges | null): void
}

function ShellBody({ compact, mobileNavigationOpen, setMobileNavigationOpen, navigationCollapsed, setNavigationCollapsed, explorerWidth, setExplorerWidth, project, projectUuid, username, pendingChanges, requestNavigation, activeWorkspace, pendingPath, setPendingPath, savingBeforeLeave, setSavingBeforeLeave, completeNavigation, setPendingChangesState }: ShellBodyProps) {
  const { t } = useTranslation()
  const { maximized } = useDocumentTabs()
  const location = useLocation()
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">{t('common.skipToContent')}</a>
      <div className="shell-content">
        <header className="topbar">
          <div className="topbar-identity">{compact && <AntActionButton aria-label={t('nav.workspaces')} aria-expanded={mobileNavigationOpen} onClick={() => setMobileNavigationOpen(value => !value)} icon={<PanelLeft size={18} />} />}<span className="compact-brand" aria-label="AKIŞ"><DatabaseZap /><strong>AKIŞ</strong></span><ProjectSwitcher currentProject={project} projectUuid={projectUuid} onNavigate={requestNavigation} /></div>
          <div className="topbar-actions">
            <LanguageSwitcher className="compact-select" />
            <ThemeSwitcher className="compact-select" />
            <div className="user-menu">
              <span className="user-avatar"><CircleUserRound size={14} /></span><span>{username}</span>
              <AntActionButton type="button" tone="ghost" aria-label={t('nav.signOut')} title={t('nav.signOut')} onClick={() => requestNavigation('/login')}><LogOut size={16} /></AntActionButton>
            </div>
          </div>
        </header>
        <div className={`shell-body ${compact ? 'is-compact' : ''}${maximized ? ' is-maximized' : ''}${!compact && navigationCollapsed ? ' is-navigation-collapsed' : ''}`} style={{ '--explorer-width': `${explorerWidth}px` } as CSSProperties}>
          {(!compact || mobileNavigationOpen) && <div className="shell-navigation-pane"><aside className={`shell-navigation${!compact && navigationCollapsed ? ' is-collapsed' : ''}`} aria-label={t('nav.workspaces')}>
            <div className="shell-navigation-content">
              <WorkspaceNavigation collapsed={!compact && navigationCollapsed} hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} />
              {(!navigationCollapsed || compact) && activeWorkspace === 'connections' && <ConnectionsSubnavigation hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} />}
              {(!navigationCollapsed || compact) && activeWorkspace === 'operations' && <OperationsSubnavigation hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} />}
              {(!navigationCollapsed || compact) && activeWorkspace === 'development' && <DesignWorkspace key={projectUuid} projectUuid={projectUuid} onNavigate={requestNavigation} explorerOnly />}
            </div>
            {!compact && <footer className="shell-navigation-footer"><AntActionButton type="button" tone="ghost" className="shell-navigation-toggle" aria-label={navigationCollapsed ? t('nav.expand') : t('nav.collapse')} title={navigationCollapsed ? t('nav.expand') : t('nav.collapse')} onClick={() => setNavigationCollapsed(value => !value)} icon={navigationCollapsed ? <PanelLeftOpen size={17} /> : <PanelLeftClose size={17} />}>{!navigationCollapsed && <span>{t('nav.collapse')}</span>}</AntActionButton></footer>}
          </aside>{!compact && !navigationCollapsed && <ExplorerResizeHandle width={explorerWidth} onChange={setExplorerWidth} />}</div>}
          <main id="main-content" className="main-content" tabIndex={-1}>
            <DocumentTabBar onNavigate={requestNavigation} dirtyPath={pendingChanges ? location.pathname : null} />
            {activeWorkspace === 'development' ? <div className="design-workspace definitions-workspace"><div className="design-content"><Outlet /></div></div> : <Outlet />}
          </main>
        </div>
        <footer className="app-copyright">© 2026 Mehmet KARACAN</footer>
      </div>
      <Dialog open={pendingPath !== null} title={t('pendingChanges.title')} eyebrow={t('pendingChanges.eyebrow')} closeLabel={t('common.close')} busy={savingBeforeLeave} onClose={() => setPendingPath(null)}>
        <p className="dialog-description">{t('pendingChanges.description')}</p>
        <footer className="dialog-actions"><AntActionButton tone="secondary" type="button" onClick={() => setPendingPath(null)}>{t('pendingChanges.stay')}</AntActionButton><AntActionButton tone="secondary" type="button" onClick={() => { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); if (path) completeNavigation(path) }}>{t('pendingChanges.discard')}</AntActionButton><AntActionButton tone="primary" type="button" disabled={savingBeforeLeave} onClick={async () => { if (!pendingChanges || !pendingPath) return; setSavingBeforeLeave(true); const saved = await pendingChanges.save(); setSavingBeforeLeave(false); if (saved) { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); completeNavigation(path) } }}>{savingBeforeLeave ? t('pendingChanges.saving') : t('pendingChanges.save')}</AntActionButton></footer>
      </Dialog>
    </div>
  )
}
