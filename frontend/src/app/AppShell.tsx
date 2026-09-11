import {
  Braces, ChevronDown, CircleUserRound, DatabaseZap, FolderKanban, Gauge,
  Languages, LogOut, Moon, Network, PanelLeftClose, PanelLeftOpen, Sun,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet, useNavigate, useParams } from 'react-router-dom'
import { apiRequest } from '../core/api/client'
import { useAuth } from '../core/auth/AuthContext'
import { PendingChangesContext, type PendingChanges } from '../core/navigation/PendingChangesContext'
import { useTheme, type ThemeMode } from '../core/theme/ThemeContext'
import { Dialog } from '../core/ui/Dialog'
import { listProjects, type Project } from '../features/projects/projectsApi'

const projectNavigation = [
  { path: '/development', key: 'nav.development', icon: Braces },
  { path: '/operations', key: 'nav.operations', icon: Gauge },
  { path: '/connections', key: 'nav.connections', icon: Network },
]

export function AppShell() {
  const { t, i18n } = useTranslation()
  const { username, logout } = useAuth()
  const { mode, setMode } = useTheme()
  const navigate = useNavigate()
  const { projectUuid } = useParams()
  const [project, setProject] = useState<Project | null>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const [projectQuery, setProjectQuery] = useState('')
  const [switcherError, setSwitcherError] = useState('')
  const [pendingChanges, setPendingChangesState] = useState<PendingChanges | null>(null)
  const [pendingPath, setPendingPath] = useState<string | null>(null)
  const [savingBeforeLeave, setSavingBeforeLeave] = useState(false)
  const [collapsed, setCollapsed] = useState(false)

  useEffect(() => {
    if (!projectUuid) {
      setProject(null)
      return
    }
    setProject(null)
    let active = true
    void apiRequest<Project>(`/api/v1/projects/${projectUuid}`)
      .then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setProject(null) })
    return () => { active = false }
  }, [projectUuid])

  const closeSwitcher = useCallback(() => setSwitcherOpen(false), [])
  const setPendingChanges = useCallback((value: PendingChanges | null) => setPendingChangesState(value), [])
  const requestNavigation = useCallback((path: string) => {
    if (pendingChanges) setPendingPath(path)
    else navigate(path)
  }, [navigate, pendingChanges])

  useEffect(() => {
    if (!switcherOpen) return
    setSwitcherError('')
    void listProjects().then(setProjects).catch(() => setSwitcherError(t('common.loadError')))
  }, [switcherOpen, t])

  const filteredProjects = projects.filter((item) => `${item.code} ${item.name}`.toLocaleLowerCase(i18n.language).includes(projectQuery.trim().toLocaleLowerCase(i18n.language)))

  const changeLanguage = (language: string) => void i18n.changeLanguage(language === 'tr' ? 'tr' : 'en')
  const themeIcon = mode === 'dark' ? <Moon size={16} /> : <Sun size={16} />

  return (
    <PendingChangesContext.Provider value={{ pendingChanges, setPendingChanges }}><div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <a className="skip-link" href="#main-content">{t('common.skipToContent')}</a>
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark" aria-hidden="true"><DatabaseZap size={21} strokeWidth={1.8} /></div>
          {!collapsed && <div><strong>Akış</strong><span>{t('brand.tagline')}</span></div>}
        </div>
        <nav aria-label={t('nav.workspace')}>
          <NavLink to="/projects" onClick={(event) => { if (pendingChanges) { event.preventDefault(); requestNavigation('/projects') } }} className={({ isActive }) => `nav-item ${isActive && !projectUuid ? 'active' : ''}`}>
            <FolderKanban size={18} /><span>{t('nav.projects')}</span>
          </NavLink>
          {projectUuid && (
            <div className="nav-section">
              {!collapsed && <p className="nav-label nav-project-label">{project?.code ?? t('nav.project')}</p>}
              {projectNavigation.map(({ path, key, icon: Icon }) => (
                <NavLink
                  key={key}
                  title={collapsed ? t(key) : undefined}
                  to={`/projects/${projectUuid}${path}`}
                  onClick={(event) => { if (pendingChanges) { event.preventDefault(); requestNavigation(`/projects/${projectUuid}${path}`) } }}
                  className={({ isActive }) => `nav-item mobile-primary ${isActive ? 'active' : ''}`}
                >
                  <Icon size={18} /><span>{t(key)}</span>
                </NavLink>
              ))}
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
          <button className="project-switcher" onClick={() => setSwitcherOpen(true)} aria-haspopup="dialog" aria-expanded={switcherOpen}>
            <span className="project-dot" />
            <span><small>{t('nav.workspace')}</small><strong>{project?.name ?? t('header.noProject')}</strong></span>
            <ChevronDown size={16} />
          </button>
          <div className="topbar-actions">
            <label className="compact-select">
              <Languages size={16} />
              <span className="sr-only">{t('header.language')}</span>
              <select value={i18n.language === 'tr' ? 'tr' : 'en'} onChange={(event) => changeLanguage(event.target.value)}>
                <option value="en">EN</option><option value="tr">TR</option>
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
              <button aria-label={t('nav.signOut')} title={t('nav.signOut')} onClick={() => { logout(); navigate('/login') }}><LogOut size={16} /></button>
            </div>
          </div>
        </header>
        <main id="main-content" className="main-content" tabIndex={-1}><Outlet /></main>
      </div>
      <Dialog open={switcherOpen} title={t('projectSwitcher.title')} eyebrow={t('projectSwitcher.eyebrow')} closeLabel={t('common.close')} onClose={closeSwitcher} className="project-switcher-dialog">
        <label>{t('projectSwitcher.search')}<input autoFocus value={projectQuery} onChange={(event) => setProjectQuery(event.target.value)} placeholder={t('projectSwitcher.searchPlaceholder')} /></label>
        {switcherError && <p className="error-banner" role="alert">{switcherError}</p>}
        <div className="project-switcher-list" role="list">
          {filteredProjects.map((item) => <button key={item.uuid} type="button" className={item.uuid === projectUuid ? 'is-current' : ''} onClick={() => { closeSwitcher(); requestNavigation(`/projects/${item.uuid}`) }}>
            <span><strong>{item.name}</strong><small>{item.code}</small></span>{item.uuid === projectUuid && <em>{t('projectSwitcher.current')}</em>}
          </button>)}
          {!switcherError && filteredProjects.length === 0 && <p>{t('projectSwitcher.empty')}</p>}
        </div>
        <footer><button className="button secondary" type="button" onClick={() => { closeSwitcher(); requestNavigation('/projects') }}>{t('projectSwitcher.allProjects')}</button></footer>
      </Dialog>
      <Dialog open={pendingPath !== null} title={t('pendingChanges.title')} eyebrow={t('pendingChanges.eyebrow')} closeLabel={t('common.close')} busy={savingBeforeLeave} onClose={() => setPendingPath(null)}>
        <p className="dialog-description">{t('pendingChanges.description')}</p>
        <footer className="dialog-actions"><button className="button secondary" type="button" onClick={() => setPendingPath(null)}>{t('pendingChanges.stay')}</button><button className="button secondary" type="button" onClick={() => { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); if (path) navigate(path) }}>{t('pendingChanges.discard')}</button><button className="button primary" type="button" disabled={savingBeforeLeave} onClick={async () => { if (!pendingChanges || !pendingPath) return; setSavingBeforeLeave(true); const saved = await pendingChanges.save(); setSavingBeforeLeave(false); if (saved) { const path = pendingPath; setPendingChangesState(null); setPendingPath(null); navigate(path) } }}>{savingBeforeLeave ? t('pendingChanges.saving') : t('pendingChanges.save')}</button></footer>
      </Dialog>
    </div></PendingChangesContext.Provider>
  )
}
