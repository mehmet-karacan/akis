import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getProject, type Project } from './projectsApi'
import { useCurrentProjectUuid } from './CurrentProjectContext'

export function ProjectOverviewPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t } = useTranslation()
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
      ? <div className="error-banner action-banner" role="alert"><span>{error}</span><button type="button" onClick={() => setReloadVersion((value) => value + 1)}>{t('common.retry')}</button></div>
      : null}
    <header className="project-entry-heading">
      <p className="eyebrow">{project?.code ?? t('common.loading')}</p>
      <h1>{project?.name ?? t('overview.title')}</h1>
      <p>{project?.description || t('overview.welcome')}</p>
    </header>
  </section>
}
