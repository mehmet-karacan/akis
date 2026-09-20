import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowRight, Cable, Database, FolderKanban, GitBranch, History, Layers3, Workflow } from 'lucide-react'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { getProject, type Project } from './projectsApi'
import { useCurrentProjectUuid } from './CurrentProjectContext'
import { topologyApi } from '../topology/api'
import { definitionsApi } from '../definitions/api'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

interface Counts { connections: number; logicalSchemas: number; environments: number; models: number; definitions: number }

/** Project entry: header, inventory summary cards and workspace shortcuts (no operational metrics). */
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
  const [counts, setCounts] = useState<Counts | null>(null)
  const [error, setError] = useState('')
  const [reloadVersion, setReloadVersion] = useState(0)

  useEffect(() => {
    let active = true
    setProject(null); setCounts(null); setError('')
    void getProject(projectUuid)
      .then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setError(t('common.loadError')) })
    // Inventory counts are decorative; a failure leaves the cards empty instead of blocking the page.
    void Promise.all([
      topologyApi.listConnections(projectUuid), topologyApi.listLogicalSchemas(projectUuid), topologyApi.listEnvironments(projectUuid),
      topologyApi.listModels(projectUuid), definitionsApi.listDefinitions(projectUuid),
    ]).then(([connections, logicalSchemas, environments, models, definitions]) => {
      if (active) setCounts({ connections: connections.length, logicalSchemas: logicalSchemas.length, environments: environments.length, models: models.length, definitions: definitions.length })
    }).catch(() => undefined)
    return () => { active = false }
  }, [projectUuid, reloadVersion, t])

  return <section className="page-stack connections-page project-entry-page">
    {error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => setReloadVersion((value) => value + 1)} /> : null}
    <section className="connection-management-panel"><PageHeader icon={<FolderKanban />} eyebrow={project?.code ?? t('common.loading')} title={project?.name ?? t('overview.title')} description={project?.description || t('overview.welcome')} /></section>
    <SummaryStrip ariaLabel={tr ? 'Proje envanteri' : 'Project inventory'} items={[
      { label: t('nav.connections'), value: counts?.connections ?? '—', icon: <Cable />, tone: 'info' },
      { label: tr ? 'Mantıksal Şema' : 'Logical Schemas', value: counts?.logicalSchemas ?? '—', icon: <GitBranch />, tone: 'teal' },
      { label: tr ? 'Ortam' : 'Environments', value: counts?.environments ?? '—', icon: <Workflow />, tone: 'neutral' },
      { label: tr ? 'Model' : 'Models', value: counts?.models ?? '—', icon: <Layers3 />, tone: 'success' },
      { label: tr ? 'Proje Nesnesi' : 'Project Objects', value: counts?.definitions ?? '—', icon: <FolderKanban />, tone: 'info' },
    ]} />
    <nav className="project-shortcuts" aria-label={t('nav.workspaces')}>{shortcuts.map(({ path, title, icon: Icon, description }) => <Link key={path} className="project-shortcut" data-shortcut={path} to={`/project/${path}`}><Icon size={28} /><div><strong>{title}</strong><p>{description}</p></div><ArrowRight size={18} /></Link>)}</nav>
  </section>
}
