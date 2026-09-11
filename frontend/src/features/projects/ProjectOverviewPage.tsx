import { ArrowRight, Braces, FileCheck2, Network, Radar, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { ExportProjectButton } from '../bundles'
import { getProject, type Project } from './projectsApi'

const steps = [
  { path: 'topology', key: 'overview.topology', text: 'overview.topologyText', icon: Network, number: '01' },
  { path: 'models', key: 'overview.catalog', text: 'overview.catalogText', icon: Radar, number: '02' },
  { path: 'definitions', key: 'overview.design', text: 'overview.designText', icon: Braces, number: '03' },
  { path: 'publications', key: 'overview.publish', text: 'overview.publishText', icon: FileCheck2, number: '04' },
]

export function ProjectOverviewPage() {
  const { projectUuid = '' } = useParams()
  const { t } = useTranslation()
  const [project, setProject] = useState<Project | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void getProject(projectUuid).then((value) => { if (active) setProject(value) })
      .catch((cause) => { if (active) setError(cause instanceof ApiProblem ? cause.message : t('common.loadError')) })
    return () => { active = false }
  }, [projectUuid, t])

  return <section className="page-stack">
    <header className="page-header project-heading">
      <div><p className="eyebrow">{project?.code ?? t('common.loading')}</p><h1>{project?.name ?? t('overview.title')}</h1><p>{project?.description || t('overview.description')}</p></div>
      <div className="project-heading-actions">
        {project && <ExportProjectButton projectUuid={project.uuid} projectCode={project.code} />}
        <div className="status-panel"><span>{t('overview.status')}</span><strong><i />{project?.status ?? t('projects.active')}</strong><small>v{project?.version ?? '—'}</small></div>
      </div>
    </header>
    {error && <div className="error-banner" role="alert">{error}</div>}
    <div className="safety-banner"><ShieldCheck size={21} /><div><strong>{t('overview.safetyTitle')}</strong><p>{t('overview.safetyText')}</p></div></div>
    <div className="workflow-heading"><div><p className="eyebrow">{t('overview.workflow')}</p><h2>{t('overview.sequence')}</h2></div><span>{t('overview.sequenceHint')}</span></div>
    <div className="workflow-grid">{steps.map(({ path, key, text, icon: Icon, number }, index) => <Link to={path} className="workflow-card" key={path}>
      <div className="workflow-icon"><Icon size={21} /></div><span className="workflow-number">{number}</span><h3>{t(key)}</h3><p>{t(text)}</p><span className="workflow-link">{t('overview.open')}<ArrowRight size={15} /></span>{index < steps.length - 1 && <i className="workflow-connector" />}
    </Link>)}</div>
  </section>
}
