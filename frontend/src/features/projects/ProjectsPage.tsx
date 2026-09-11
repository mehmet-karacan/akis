import { ArrowUpRight, Database, FolderInput, Plus, RotateCcw } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { Dialog } from '../../core/ui/Dialog'
import { formatDate } from '../../core/i18n/formatters'
import { createProject, listProjects, type Project } from './projectsApi'

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
      navigate(`/projects/${project.uuid}`)
    } catch (cause) {
      setError(cause instanceof ApiProblem ? cause.message : t('common.saveError'))
    } finally { setCreating(false) }
  }

  return (
    <section className="page-stack">
      <header className="page-header">
        <div><p className="eyebrow">{t('projects.eyebrow')}</p><h1>{t('projects.title')}</h1><p>{t('projects.description')}</p></div>
        <div className="page-actions">
          <button className="button secondary" onClick={() => navigate('/projects/import')}><FolderInput size={17} />{t('projects.import')}</button>
          <button className="button primary" onClick={() => setShowCreate(true)}><Plus size={17} />{t('projects.new')}</button>
        </div>
      </header>

      {error && <div className="error-banner action-banner" role="alert"><span>{error}</span><button onClick={() => void load()}><RotateCcw size={15} />{t('common.retry')}</button></div>}
      {loading ? <div className="project-grid" aria-label={t('common.loading')}>{[1, 2, 3].map((item) => <div className="project-card skeleton" key={item} />)}</div> :
        projects.length === 0 ? <div className="empty-state"><Database size={30} /><h2>{t('projects.empty')}</h2><button className="button secondary" onClick={() => setShowCreate(true)}>{t('projects.new')}</button></div> :
          <div className="project-grid">{projects.map((project) => (
            <button className="project-card" key={project.uuid} onClick={() => navigate(`/projects/${project.uuid}`)}>
              <div className="project-card-top"><span className="code-badge">{project.code}</span><ArrowUpRight size={18} /></div>
              <h2>{project.name}</h2><p>{project.description || t('common.noDescription')}</p>
              <footer><time>{formatDate(project.createdAt, i18n.language)}</time></footer>
            </button>
          ))}</div>}

      <Dialog open={showCreate} title={t('projects.new')} eyebrow={t('projects.eyebrow')} closeLabel={t('common.close')} busy={creating} onClose={() => setShowCreate(false)}>
          <form onSubmit={create}>
            <label>{t('projects.code')}<input name="code" pattern="[A-Za-z][A-Za-z0-9_-]{1,39}" required placeholder="FINANCE_DWH" /></label>
            <label>{t('projects.name')}<input name="name" required /></label>
            <label>{t('projects.descriptionField')}<textarea name="description" rows={4} /></label>
            <footer><button className="button secondary" type="button" onClick={() => setShowCreate(false)}>{t('common.cancel')}</button><button className="button primary" disabled={creating}>{creating ? t('projects.creating') : t('projects.create')}</button></footer>
          </form>
      </Dialog>
    </section>
  )
}
