import {
  Braces, ChevronDown, CircleUserRound, DatabaseZap, FolderKanban, Gauge,
  Languages, LogOut, Moon, Network, PanelLeftClose, PanelLeftOpen, Sun,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet, useNavigate, useParams } from 'react-router-dom'
import { apiRequest } from '../core/api/client'
import { useAuth } from '../core/auth/AuthContext'
import { useTheme, type ThemeMode } from '../core/theme/ThemeContext'
import type { Project } from '../features/projects/projectsApi'

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
  const [collapsed, setCollapsed] = useState(false)

  useEffect(() => {
    if (!projectUuid) {
      setProject(null)
      return
    }
    let active = true
    void apiRequest<Project>(`/api/v1/projects/${projectUuid}`)
      .then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setProject(null) })
    return () => { active = false }
  }, [projectUuid])

  const changeLanguage = (language: string) => void i18n.changeLanguage(language === 'tr' ? 'tr' : 'en')
  const themeIcon = mode === 'dark' ? <Moon size={16} /> : <Sun size={16} />

  return (
    <div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark" aria-hidden="true"><DatabaseZap size={21} strokeWidth={1.8} /></div>
          {!collapsed && <div><strong>Akış</strong><span>{t('brand.tagline')}</span></div>}
        </div>
        <nav aria-label={t('nav.workspace')}>
          <NavLink to="/projects" className={({ isActive }) => `nav-item ${isActive && !projectUuid ? 'active' : ''}`}>
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
          <button className="project-switcher" onClick={() => navigate(projectUuid ? `/projects/${projectUuid}` : '/projects')}>
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
        <main className="main-content"><Outlet /></main>
      </div>
    </div>
  )
}
