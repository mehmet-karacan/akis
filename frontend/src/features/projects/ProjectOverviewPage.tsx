import { Button as AntActionButton } from '../../core/ui/Button'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getProject, type Project } from './projectsApi'
import { useCurrentProjectUuid } from './CurrentProjectContext'
import { Link } from 'react-router-dom'
import { ArrowRight, Cable, FolderKanban, History, Database } from 'lucide-react'

export function ProjectOverviewPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const shortcuts = [
    { path: 'objects', title: t('nav.development'), icon: FolderKanban, description: tr ? 'Prosedürleri, değişkenleri ve veri akışlarını tasarlayın.' : 'Design procedures, variables and data flows.' },
    { path: 'connections', title: t('nav.connections'), icon: Cable, description: tr ? 'Veri bağlantılarını, şemaları ve ortamları yönetin.' : 'Manage database connections, schemas and environments.' },
    { path: 'operations', title: t('nav.operations'), icon: History, description: tr ? 'Çalıştırma sonuçlarını, adımları ve hataları izleyin.' : 'Inspect execution results, steps and errors.' },
    { path: 'models', title: tr ? 'Modeller' : 'Models', icon: Database, description: tr ? 'Veri modellerini ve tablo yapılarını inceleyin.' : 'Explore data models and table structures.' },
  ]
  const [project, setProject] = useState<Project | null>(null)
  const [error, setError] = useState('')
  const [reloadVersion, setReloadVersion] = useState(0)

  useEffect(() => {
    let active = true
    setProject(null)
    setError('')
    void getProject(projectUuid)
      .then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setError(t('common.loadError')) })
    return () => { active = false }
  }, [projectUuid, reloadVersion, t])

  return <section className="page-stack project-entry-page">
    {error
      ? <div className="error-banner action-banner" role="alert"><span>{error}</span><AntActionButton tone="ghost" type="button" onClick={() => setReloadVersion((value) => value + 1)}>{t('common.retry')}</AntActionButton></div>
      : null}
    <header className="project-entry-heading">
      <p className="eyebrow">{project?.code ?? t('common.loading')}</p>
      <h1>{project?.name ?? t('overview.title')}</h1>
      <p>{project?.description || t('overview.welcome')}</p>
    </header>
    <nav className="project-shortcuts" aria-label={t('nav.workspaces')}>{shortcuts.map(({ path, title, icon: Icon, description }) => <Link key={path} className="project-shortcut" to={`/project/${path}`}><Icon size={28} /><div><strong>{title}</strong><p>{description}</p></div><ArrowRight size={18} /></Link>)}</nav>
  </section>
}
