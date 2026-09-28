import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ArrowRight, Cable, FolderKanban, GitBranch, Globe2, Layers3, PlayCircle, Workflow } from 'lucide-react'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { getProject, type Project } from './projectsApi'
import { useCurrentProjectUuid } from './CurrentProjectContext'
import { topologyApi } from '../topology/api'
import { definitionsApi } from '../definitions/api'
import type { Definition } from '../definitions/types'
import { DefinitionTypeIcon } from '../definitions/DefinitionTypeIcon'
import { definitionTypeKey, useDefinitionsI18n } from '../definitions/i18n'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

interface Counts { connections: number; logicalSchemas: number; environments: number; models: number; definitions: number }

/** Project entry: header, inventory summary cards and workspace shortcuts (no operational metrics). */
export function ProjectOverviewPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const { t: definitionT } = useDefinitionsI18n()
  const tr = i18n.language === 'tr'
  const shortcuts = [
    { path: 'objects', title: t('nav.development'), icon: Workflow, description: tr ? 'Prosedürleri, değişkenleri ve veri akışlarını tasarlayın.' : 'Design procedures, variables and data flows.' },
    { path: 'connections', title: t('nav.connections'), icon: Cable, description: tr ? 'Veri bağlantılarını, şemaları ve ortamları yönetin.' : 'Manage database connections, schemas and environments.' },
    { path: 'operations', title: t('nav.operations'), icon: PlayCircle, description: tr ? 'Çalıştırma sonuçlarını, adımları ve hataları izleyin.' : 'Inspect execution results, steps and errors.' },
    { path: 'models', title: tr ? 'Modeller' : 'Models', icon: Layers3, description: tr ? 'Veri modellerini ve tablo yapılarını inceleyin.' : 'Explore data models and table structures.' },
  ]
  const [project, setProject] = useState<Project | null>(null)
  const [counts, setCounts] = useState<Counts | null>(null)
  const [recentDefinitions, setRecentDefinitions] = useState<Definition[] | null>(null)
  const [error, setError] = useState('')
  const [reloadVersion, setReloadVersion] = useState(0)

  useEffect(() => {
    let active = true
    setProject(null); setCounts(null); setRecentDefinitions(null); setError('')
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
    void definitionsApi.listRecentDefinitions(projectUuid).then(rows => { if (active) setRecentDefinitions(rows) }).catch(() => { if (active) setRecentDefinitions([]) })
    return () => { active = false }
  }, [projectUuid, reloadVersion, t])

  return <section className="page-stack connections-page project-entry-page">
    {error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => setReloadVersion((value) => value + 1)} /> : null}
    <section className="connection-management-panel"><PageHeader icon={<FolderKanban />} eyebrow={project?.code ?? t('common.loading')} title={project?.name ?? t('overview.title')} description={project?.description || t('overview.welcome')} /></section>
    <SummaryStrip ariaLabel={tr ? 'Proje envanteri' : 'Project inventory'} items={[
      { label: t('nav.connections'), value: counts?.connections ?? '—', icon: <Cable />, tone: 'teal' },
      { label: tr ? 'Mantıksal Şema' : 'Logical Schemas', value: counts?.logicalSchemas ?? '—', icon: <GitBranch />, tone: 'neutral' },
      { label: tr ? 'Ortam' : 'Environments', value: counts?.environments ?? '—', icon: <Globe2 />, tone: 'warning' },
      { label: tr ? 'Model' : 'Models', value: counts?.models ?? '—', icon: <Layers3 />, tone: 'info' },
      { label: tr ? 'Tasarlanan Nesne' : 'Designed Objects', value: counts?.definitions ?? '—', icon: <Workflow />, tone: 'neutral' },
    ]} />
    <section className="project-entry-workspaces" aria-label={t('nav.workspaces')}>
      <header><h2>{tr ? 'Çalışma Alanları' : 'Workspaces'}</h2><p>{tr ? 'İşinize devam etmek için ilgili alana geçin.' : 'Choose a workspace to continue your work.'}</p></header>
      <nav className="project-shortcuts" aria-label={t('nav.workspaces')}>{shortcuts.map(({ path, title, icon: Icon, description }) => <Link key={path} className="project-shortcut" data-shortcut={path} to={`/project/${path}`}><span className="project-shortcut-icon"><Icon size={18} /></span><div><strong>{title}</strong><p>{description}</p></div><ArrowRight size={18} /></Link>)}</nav>
    </section>
    <section className="project-entry-recent" aria-label={tr ? 'Son tanımlanan nesneler' : 'Recently defined objects'}>
      <header><div><h2>{tr ? 'Son Tanımlanan 5 Nesne' : '5 Most Recently Defined Objects'}</h2><p>{tr ? 'Son eklenen nesnelerin türünü ve kayıt bilgilerini inceleyin.' : 'Review the newest objects and their record details.'}</p></div><Link to="/project/objects">{tr ? 'Akış Tasarımına Git' : 'Open Flow Design'} <ArrowRight size={16} /></Link></header>
      {recentDefinitions === null ? <p className="form-note">{t('common.loading')}</p> : recentDefinitions.length ? <DataGrid viewControls={false} auditKind="definitions" aria-label={tr ? 'Son tanımlanan 5 nesne tablosu' : '5 most recently defined objects table'}>
        <thead><tr><th>{tr ? 'Nesne' : 'Object'}</th><th>{tr ? 'Tür' : 'Type'}</th><th>{tr ? 'Kısa Kod' : 'Short Code'}</th><th>{tr ? 'İşlem' : 'Action'}</th></tr></thead>
        <tbody>{recentDefinitions.map(item => <tr key={item.uuid}>
          <td><Link className="project-recent-object-link" to={`/project/objects/definitions/${encodeURIComponent(item.uuid)}`}><DefinitionTypeIcon type={item.type} /><strong>{item.name}</strong></Link></td>
          <td>{definitionT(definitionTypeKey[item.type])}</td><td>{item.code}</td>
          <td><Link className="project-recent-open-link" to={`/project/objects/definitions/${encodeURIComponent(item.uuid)}`} aria-label={`${item.name} ${tr ? 'aç' : 'open'}`}><ArrowRight size={16} /></Link></td>
        </tr>)}</tbody>
      </DataGrid> : <p className="form-note">{tr ? 'Henüz tanımlanan nesne yok.' : 'No objects have been defined yet.'}</p>}
    </section>
  </section>
}
