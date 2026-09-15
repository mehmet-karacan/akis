import { Input as AntInput } from 'antd'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Check, Database, FolderInput, Plus, RotateCcw } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { Dialog } from '../../core/ui/Dialog'
import { formatDate } from '../../core/i18n/formatters'
import { createProject, listProjects, type Project } from './projectsApi'
import { getRememberedProject, rememberProject } from './projectPreference'

export function ProjectsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [creating, setCreating] = useState(false)

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
    setError('')
    try {
      const project = await createProject({
        code: String(form.get('code')).trim().toUpperCase(),
        name: String(form.get('name')).trim(),
        description: String(form.get('description')).trim() || undefined,
      })
      openProject(project.uuid)
    } catch (cause) {
      setError(cause instanceof ApiProblem ? cause.message : t('common.saveError'))
    } finally { setCreating(false) }
  }

  return (
    <section className="project-choice-page">
      {error && <div className="error-banner action-banner" role="alert"><span>{error}</span><AntActionButton type="submit" tone="primary" onClick={() => void load()}><RotateCcw size={15} />{t('common.retry')}</AntActionButton></div>}
      <div className="project-choice-panel">
        <header><span className="project-choice-icon"><Database /></span><div><p className="eyebrow">{t('projects.eyebrow')}</p><h1>{t('projects.title')}</h1><p>{t('projects.description')}</p></div></header>
        {loading ? <div className="project-choice-loading" aria-label={t('common.loading')}><span /><span /><span /></div> : projects.length === 0 ? <div className="project-choice-empty"><p>{t('projects.empty')}</p><div><AntActionButton type="submit" tone="secondary" onClick={() => navigate('/project/import')}><FolderInput size={17} />{t('projects.import')}</AntActionButton><AntActionButton type="submit" tone="primary" onClick={() => setShowCreate(true)}><Plus size={17} />{t('projects.new')}</AntActionButton></div></div> : <div className="project-choice-list" role="list">{projects.map((project) => (
          <AntActionButton tone="ghost" type="button" key={project.uuid} onClick={() => openProject(project.uuid)}>
            <span><strong>{project.name}</strong><small>{project.code} · {formatDate(project.createdAt, i18n.language)}</small></span><Check aria-hidden="true" />
          </AntActionButton>
        ))}</div>}
      </div>

      <Dialog open={showCreate} title={t('projects.new')} eyebrow={t('projects.eyebrow')} closeLabel={t('common.close')} busy={creating} onClose={() => setShowCreate(false)}>
          <form onSubmit={create}>
            <label>{t('projects.code')}<AntInput name="code" pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required placeholder="FINANCE_DWH" /></label>
            <label>{t('projects.name')}<AntInput name="name" required /></label>
            <label>{t('projects.descriptionField')}<AntInput.TextArea name="description" rows={4} /></label>
            <footer><AntActionButton tone="secondary" type="button" onClick={() => setShowCreate(false)}>{t('common.cancel')}</AntActionButton><AntActionButton type="submit" tone="primary" disabled={creating}>{creating ? t('projects.creating') : t('projects.create')}</AntActionButton></footer>
          </form>
      </Dialog>
    </section>
  )
}
