import { Input as AntInput, Tag } from 'antd'
import { CheckCircle2, CircleAlert, Database, FolderInput, FolderKanban, Plus, RotateCcw } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { AsyncState, Button, Dialog, PageHeader, RecordActionButton, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { formatDate } from '../../core/i18n/formatters'
import { connectionStatusTagStyles } from '../connections/presentation'
import { createProject, listProjects, type Project } from './projectsApi'
import { getRememberedProject, rememberProject } from './projectPreference'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import '../topology/topology.css'

/** Project selection in the shared catalog layout; a single visible project opens automatically. */
export function ProjectsPage() {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const navigate = useNavigate()
  const [view, setView] = useCollectionView('akis:projects:view')
  const [params, setParams] = useSearchParams()
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try { setProjects(await listProjects()) }
    catch (cause) { setError(cause instanceof ApiProblem ? cause.message : t('common.loadError')) }
    finally { setLoading(false) }
  }

  useEffect(() => { void load() }, [])
  useEffect(() => {
    if (loading) return
    const selected = projects.length === 1 ? projects[0] : projects.find((item) => item.uuid === getRememberedProject())
    if (selected) { rememberProject(selected.uuid); navigate('/project', { replace: true }) }
  }, [loading, navigate, projects])

  function openProject(projectUuid: string) {
    rememberProject(projectUuid)
    navigate('/project')
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setCreating(true)
    setCreateError('')
    try {
      const project = await createProject({
        code: String(form.get('code')).trim().toUpperCase(),
        name: String(form.get('name')).trim(),
        description: String(form.get('description')).trim() || undefined,
      })
      openProject(project.uuid)
    } catch (cause) {
      setCreateError(cause instanceof ApiProblem ? cause.message : t('common.saveError'))
    } finally { setCreating(false) }
  }

  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})
  const filtered = useMemo(() => projects
    .filter((project) => `${project.name} ${project.code} ${project.description ?? ''}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => a.name.localeCompare(b.name, i18n.language)), [projects, query, i18n.language])
  const active = (project: Project) => project.status === 'AKTIF' || project.status === 'ETKIN'
  const actions = <>
    <Button tone="secondary" icon={<FolderInput size={16} />} onClick={() => navigate('/project/import')}>{t('projects.import')}</Button>
    <Button tone="primary" icon={<Plus size={16} />} onClick={() => setShowCreate(true)}>{t('projects.new')}</Button>
  </>

  return <section className="page-stack connections-page projects-page">
    <section className="connection-management-panel"><PageHeader icon={<FolderKanban />} eyebrow={t('projects.eyebrow')} title={t('projects.title')} description={t('projects.description')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Ad, kod veya açıklamaya göre ara' : 'Search by name, code or description'} /></section>
    <SummaryStrip ariaLabel={t('projects.title')} items={[
      { label: tr ? 'Toplam Proje' : 'Total Projects', value: projects.length, icon: <FolderKanban />, tone: 'info' },
      { label: tr ? 'Etkin Proje' : 'Active Projects', value: projects.filter(active).length, icon: <CheckCircle2 />, tone: 'success' },
      { label: tr ? 'Son Açılan' : 'Last Opened', value: projects.find((item) => item.uuid === getRememberedProject())?.code ?? '—', icon: <Database />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
    {error && <div className="error-banner action-banner" role="alert"><span>{error}</span><Button tone="primary" icon={<RotateCcw size={15} />} onClick={() => void load()}>{t('common.retry')}</Button></div>}
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('projects.empty')} action={<span className="connection-row-actions">{actions}</span>} /> : <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid collectionTitle={tr ? 'Proje Kataloğu' : 'Project Catalog'} collectionIcon={<FolderKanban />} toolbarActions={actions} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="name">{tr ? 'Proje' : 'Project'}</th><th data-field-key="description">{t('projects.descriptionField')}</th><th data-field-key="status">{tr ? 'Durum' : 'Status'}</th><th data-field-key="createdAt">{tr ? 'Oluşturulma' : 'Created'}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{visible.map((project) => { const ok = active(project); const tone = ok ? 'success' : 'warning'; return <tr key={project.uuid} data-connection-uuid={project.uuid}>
        <td><span className="connection-record-identity"><strong>{project.name}</strong><small>{project.code}</small></span></td>
        <td>{project.description || t('common.noDescription')}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{ok ? (tr ? 'Etkin' : 'Active') : project.status}</span></Tag></td>
        <td>{formatDate(project.createdAt, i18n.language)}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={project.name} editable={false} onClick={() => openProject(project.uuid)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>

    <Dialog open={showCreate} title={t('projects.new')} eyebrow={t('projects.eyebrow')} closeLabel={t('common.close')} busy={creating} onClose={() => setShowCreate(false)} className="connection-catalog-dialog">
      <form className="topology-connection-form topology-connection-form--simple" onSubmit={create}>
        <div className="topology-form topology-form--grid">
          <label className="topology-field"><span>{t('projects.code')}</span><AntInput name="code" pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required placeholder="FINANCE_DWH" /></label>
          <label className="topology-field"><span>{t('projects.name')}</span><AntInput name="name" required /></label>
          <label className="topology-field topology-field--wide"><span>{t('projects.descriptionField')}</span><AntInput.TextArea name="description" rows={3} /></label>
        </div>
        {createError && <div className="topology-inline-error" role="alert"><CircleAlert />{createError}<Button tone="ghost" type="button" icon={<RotateCcw size={14} />} onClick={() => setCreateError('')}>{t('common.retry')}</Button></div>}
        <footer className="topology-form-actions">
          <Button tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={() => setShowCreate(false)}>{t('common.cancel')}</Button>
          <Button type="submit" tone="primary" className="topology-button" busy={creating}>{creating ? t('projects.creating') : t('projects.create')}</Button>
        </footer>
      </form>
    </Dialog>
  </section>
}
