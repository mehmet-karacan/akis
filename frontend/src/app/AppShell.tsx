import {
  CircleUserRound, DatabaseZap, Languages, LogOut, Moon, Sun,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { apiRequest } from '../core/api/client'
import { useAuth } from '../core/auth/AuthContext'
import { ProjectAccessProvider, type ProjectAccess } from '../core/auth/ProjectAccessContext'
import { PendingChangesContext, type PendingChanges } from '../core/navigation/PendingChangesContext'
import { useTheme, type ThemeMode } from '../core/theme/ThemeContext'
import { Dialog } from '../core/ui/Dialog'
import type { Project } from '../features/projects/projectsApi'
import { CurrentProjectProvider } from '../features/projects/CurrentProjectContext'
import { getRememberedProject } from '../features/projects/projectPreference'
import { ProjectSwitcher } from './ProjectSwitcher'
import { ConnectionsSubnavigation } from './ConnectionsSubnavigation'
import { resolveWorkspace, WorkspaceNavigation } from './WorkspaceNavigation'
import { DesignWorkspace } from './DesignWorkspace'

export function AppShell() {
  const { t, i18n } = useTranslation()
  const { username, logout } = useAuth()
  const { mode, setMode } = useTheme()
  const navigate = useNavigate()
  const location = useLocation()
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

  const changeLanguage = (language: string) => void i18n.changeLanguage(language === 'tr' ? 'tr' : 'en')
  const themeIcon = mode === 'dark' ? <Moon size={16} /> : <Sun size={16} />

  useEffect(() => {
    if (!pendingChanges) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pendingChanges])

  if (!projectUuid) return <Navigate to="/project/select" replace />

  return (
    <CurrentProjectProvider projectUuid={projectUuid}><ProjectAccessProvider value={projectAccess}><PendingChangesContext.Provider value={{ pendingChanges, setPendingChanges }}><div className="app-shell">
      <a className="skip-link" href="#main-content">{t('common.skipToContent')}</a>
      <div className="shell-content">
        <header className="topbar">
          <div className="topbar-identity"><span className="compact-brand" aria-label="AKIŞ"><DatabaseZap /><strong>AKIŞ</strong></span><ProjectSwitcher currentProject={project} projectUuid={projectUuid} onNavigate={requestNavigation} /></div>
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
        {projectUuid ? <WorkspaceNavigation hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} /> : null}
        {projectUuid && activeWorkspace === 'connections' ? <ConnectionsSubnavigation hasPendingChanges={Boolean(pendingChanges)} onNavigate={requestNavigation} /> : null}
        <main id="main-content" className="main-content" tabIndex={-1}>{activeWorkspace === 'development' ? <DesignWorkspace key={projectUuid} projectUuid={projectUuid} onNavigate={requestNavigation}><Outlet /></DesignWorkspace> : <Outlet />}</main>
      </div>
      <Dialog open={pendingPath !== null} title={t('pendingChanges.title')} eyebrow={t('pendingChanges.eyebrow')} closeLabel={t('common.close')} busy={savingBeforeLeave} onClose={() => setPendingPath(null)}>
        <p className="dialog-description">{t('pendingChanges.description')}</p>
        <footer className="dialog-actions"><button className="button secondary" type="button" onClick={() => setPendingPath(null)}>{t('pendingChanges.stay')}</button><button className="button secondary" type="button" onClick={() => { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); if (path) completeNavigation(path) }}>{t('pendingChanges.discard')}</button><button className="button primary" type="button" disabled={savingBeforeLeave} onClick={async () => { if (!pendingChanges || !pendingPath) return; setSavingBeforeLeave(true); const saved = await pendingChanges.save(); setSavingBeforeLeave(false); if (saved) { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); completeNavigation(path) } }}>{savingBeforeLeave ? t('pendingChanges.saving') : t('pendingChanges.save')}</button></footer>
      </Dialog>
    </div></PendingChangesContext.Provider></ProjectAccessProvider></CurrentProjectProvider>
  )
}
