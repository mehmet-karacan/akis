import {
  CircleUserRound, DatabaseZap,
  Languages, LogOut, Moon, PanelLeftClose, PanelLeftOpen, Sun,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { apiRequest } from '../core/api/client'
import { useAuth } from '../core/auth/AuthContext'
import { ProjectAccessProvider, type ProjectAccess } from '../core/auth/ProjectAccessContext'
import { PendingChangesContext, type PendingChanges } from '../core/navigation/PendingChangesContext'
import { useTheme, type ThemeMode } from '../core/theme/ThemeContext'
import { Dialog } from '../core/ui/Dialog'
import type { Project } from '../features/projects/projectsApi'
import { definitionsApi } from '../features/definitions/api'
import type { Definition, Folder } from '../features/definitions/types'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import { ProjectSwitcher } from './ProjectSwitcher'
import { ConnectionsSubnavigation } from './ConnectionsSubnavigation'
import { resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'

export function AppShell() {
  const { t, i18n } = useTranslation()
  const { username, logout } = useAuth()
  const { mode, setMode } = useTheme()
  const navigate = useNavigate()
  const location = useLocation()
  const { projectUuid } = useParams()
  const [project, setProject] = useState<Project | null>(null)
  const [projectAccess, setProjectAccess] = useState<ProjectAccess | null>(null)
  const [pendingChanges, setPendingChangesState] = useState<PendingChanges | null>(null)
  const [pendingPath, setPendingPath] = useState<string | null>(null)
  const [savingBeforeLeave, setSavingBeforeLeave] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
  const [folders, setFolders] = useState<Folder[]>([])
  const [definitions, setDefinitions] = useState<Definition[]>([])
  const [objectsLoading, setObjectsLoading] = useState(false)
  const [objectsFailed, setObjectsFailed] = useState(false)
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
    if (pendingChanges) setPendingPath(path)
    else completeNavigation(path)
  }, [completeNavigation, pendingChanges])

  const loadProjectObjects = useCallback(async () => {
    if (!projectUuid) { setFolders([]); setDefinitions([]); return }
    setObjectsLoading(true); setObjectsFailed(false)
    try {
      const [nextFolders, nextDefinitions] = await Promise.all([
        definitionsApi.listFolders(projectUuid), definitionsApi.listDefinitions(projectUuid),
      ])
      setFolders(nextFolders); setDefinitions(nextDefinitions)
    } catch { setObjectsFailed(true) }
    finally { setObjectsLoading(false) }
  }, [projectUuid])

  useEffect(() => { void loadProjectObjects() }, [loadProjectObjects])
  useEffect(() => {
    const reload = () => void loadProjectObjects()
    window.addEventListener('akis:definitions-changed', reload)
    return () => window.removeEventListener('akis:definitions-changed', reload)
  }, [loadProjectObjects])

  const activeWorkspace = resolveWorkspace(location.pathname)

  const changeLanguage = (language: string) => void i18n.changeLanguage(language === 'tr' ? 'tr' : 'en')
  const themeIcon = mode === 'dark' ? <Moon size={16} /> : <Sun size={16} />

  useEffect(() => {
    if (!pendingChanges) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pendingChanges])

  return (
    <ProjectAccessProvider value={projectAccess}><PendingChangesContext.Provider value={{ pendingChanges, setPendingChanges }}><div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <a className="skip-link" href="#main-content">{t('common.skipToContent')}</a>
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark" aria-hidden="true"><DatabaseZap size={21} strokeWidth={1.8} /></div>
          {!collapsed && <div><strong>AKIŞ</strong><span>{t('brand.tagline')}</span></div>}
        </div>
        {collapsed && project ? <span className="collapsed-project-mark" title={`${project.name} (${project.code})`}>{project.code.slice(0, 2).toLocaleUpperCase(i18n.language)}</span> : null}
        <nav aria-label={t('nav.workspace')}>
          {projectUuid && (
            <div className="nav-section">
              <WorkspaceNavigation projectUuid={projectUuid} collapsed={collapsed} hasPendingChanges={Boolean(pendingChanges)} operatorOnly={projectAccess?.roles.includes('OPERASYON') === true && !projectAccess.permissions.includes('TANIM_DUZENLE')} onNavigate={requestNavigation} />
              {!collapsed && activeWorkspace === 'development' ? <div className="workspace-subnavigation">
                <p className="nav-label">{t('nav.projectObjects')}</p>
                <ProjectSidebarTree
                  projectUuid={projectUuid}
                  folders={folders}
                  definitions={definitions}
                  selectedUuid={location.pathname.match(/\/development\/definitions\/([^/]+)$/)?.[1] ?? new URLSearchParams(location.search).get('definition')}
                  loading={objectsLoading}
                  failed={objectsFailed}
                  onNavigate={requestNavigation}
                  onRetry={() => void loadProjectObjects()}
                />
              </div> : null}
              {!collapsed && activeWorkspace === 'operations' ? <div className="workspace-subnavigation workspace-summary">
                <p className="nav-label">{t('nav.operationsContext')}</p>
                <span>{t('nav.runs')}</span>
              </div> : null}
              {!collapsed && activeWorkspace === 'connections' ? <ConnectionsSubnavigation projectUuid={projectUuid} hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} /> : null}
            </div>
          )}
        </nav>
        <button
          className="collapse-button"
          onClick={() => setCollapsed((value) => !value)}
          aria-expanded={!collapsed}
          aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
        >
          {collapsed ? <PanelLeftOpen size={18} /> : <><PanelLeftClose size={18} /><span>{t('nav.collapse')}</span></>}
        </button>
      </aside>

      <div className="shell-content">
        <header className="topbar">
          <ProjectSwitcher currentProject={project} projectUuid={projectUuid} onNavigate={requestNavigation} />
          <div className="topbar-actions">
            <label className="compact-select">
              <Languages size={16} />
              <span className="sr-only">{t('header.language')}</span>
              <select value={i18n.language === 'tr' ? 'tr' : 'en'} onChange={(event) => changeLanguage(event.target.value)}>
                <option value="en">English</option><option value="tr">Türkçe</option>
              </select>
            </label>
            <label className="compact-select">
              {themeIcon}<span className="sr-only">{t('header.theme')}</span>
              <select value={mode} onChange={(event) => setMode(event.target.value as ThemeMode)}>
                <option value="light">{t('theme.light')}</option>
                <option value="dark">{t('theme.dark')}</option>
                <option value="system">{t('theme.system')}</option>
              </select>
            </label>
            <div className="user-menu">
              <CircleUserRound size={18} /><span>{username}</span>
              <button aria-label={t('nav.signOut')} title={t('nav.signOut')} onClick={() => requestNavigation('/login')}><LogOut size={16} /></button>
            </div>
          </div>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}><Outlet /></main>
      </div>
      <Dialog open={pendingPath !== null} title={t('pendingChanges.title')} eyebrow={t('pendingChanges.eyebrow')} closeLabel={t('common.close')} busy={savingBeforeLeave} onClose={() => setPendingPath(null)}>
        <p className="dialog-description">{t('pendingChanges.description')}</p>
        <footer className="dialog-actions"><button className="button secondary" type="button" onClick={() => setPendingPath(null)}>{t('pendingChanges.stay')}</button><button className="button secondary" type="button" onClick={() => { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); if (path) completeNavigation(path) }}>{t('pendingChanges.discard')}</button><button className="button primary" type="button" disabled={savingBeforeLeave} onClick={async () => { if (!pendingChanges || !pendingPath) return; setSavingBeforeLeave(true); const saved = await pendingChanges.save(); setSavingBeforeLeave(false); if (saved) { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); completeNavigation(path) } }}>{savingBeforeLeave ? t('pendingChanges.saving') : t('pendingChanges.save')}</button></footer>
      </Dialog>
    </div></PendingChangesContext.Provider></ProjectAccessProvider>
  )
}
