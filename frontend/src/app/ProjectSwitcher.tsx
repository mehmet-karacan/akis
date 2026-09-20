import { Button as AntActionButton } from '../core/ui/Button'
import { Input as AntInput } from 'antd'
import { ChevronDown, FolderOpenDot } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog } from '../core/ui/Dialog'
import { ExportProjectButton } from '../features/bundles/ExportProjectButton'
import { listProjects, type Project } from '../features/projects/projectsApi'
import { rememberProject } from '../features/projects/projectPreference'

export function ProjectSwitcher({ currentProject, projectUuid, onNavigate }: {
  currentProject: Project | null
  projectUuid?: string
  onNavigate: (path: string) => void
}) {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState(false)
  const [projects, setProjects] = useState<Project[]>([])
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    let active = true
    setError('')
    void listProjects()
      .then((items) => { if (active) setProjects(items) })
      .catch(() => { if (active) setError(t('common.loadError')) })
    return () => { active = false }
  }, [open, t])

  const filtered = projects.filter((item) => `${item.code} ${item.name}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
  return <>
    <AntActionButton type="button" tone="ghost" className="project-switcher" onClick={() => setOpen(true)} aria-haspopup="dialog" aria-expanded={open} title={currentProject?.name}>
      <span className="project-icon"><FolderOpenDot size={16} /></span>
      <span><small className="sr-only">{t('nav.workspace')}</small><strong>{currentProject?.name ?? t('header.noProject')}</strong></span>
      <ChevronDown size={15} className="project-switcher-chevron" />
    </AntActionButton>
    <Dialog open={open} title={t('projectSwitcher.title')} eyebrow={t('projectSwitcher.eyebrow')} closeLabel={t('common.close')} onClose={() => setOpen(false)} className="project-switcher-dialog">
      <label>{t('projectSwitcher.search')}<AntInput autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t('projectSwitcher.searchPlaceholder')} /></label>
      {error && <p className="error-banner" role="alert">{error}</p>}
      <div className="project-switcher-list" role="list">
        {filtered.map((item) => <AntActionButton tone="ghost" key={item.uuid} type="button" className={item.uuid === projectUuid ? 'is-current' : ''} onClick={() => { rememberProject(item.uuid); setOpen(false); onNavigate('/project') }}>
          <span><strong>{item.name}</strong><small>{item.code}</small></span>{item.uuid === projectUuid && <em>{t('projectSwitcher.current')}</em>}
        </AntActionButton>)}
        {!error && filtered.length === 0 && <p>{t('projectSwitcher.empty')}</p>}
      </div>
      <footer>{projectUuid && currentProject ? <ExportProjectButton projectUuid={projectUuid} projectCode={currentProject.code} className="project-switcher-export" /> : null}</footer>
    </Dialog>
  </>
}
