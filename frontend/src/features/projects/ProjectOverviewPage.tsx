import { Activity, ArrowRight, Braces, CheckCircle2, CircleAlert, FileCheck2, Gauge, Network, Radar, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { ExportProjectButton } from '../bundles'
import { executionApi } from '../execution/api'
import { RunStatusBadge } from '../execution/RunStatusBadge'
import type { RunRecord } from '../execution/types'
import { formatDate } from '../operations/utils'
import { getProject, type Project } from './projectsApi'

const steps = [
  { path: 'topology', key: 'overview.topology', text: 'overview.topologyText', icon: Network, number: '01' },
  { path: 'models', key: 'overview.catalog', text: 'overview.catalogText', icon: Radar, number: '02' },
  { path: 'definitions', key: 'overview.design', text: 'overview.designText', icon: Braces, number: '03' },
  { path: 'publications', key: 'overview.publish', text: 'overview.publishText', icon: FileCheck2, number: '04' },
]

const runningStatuses = new Set(['BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT'])
const attentionStatuses = new Set(['BASARISIZ', 'SONUC_BELIRSIZ', 'MUDAHALE_GEREKLI', 'YENIDEN_DENENEBILIR'])

export function summarizeRuns(runs: RunRecord[]) {
  return {
    running: runs.filter((run) => runningStatuses.has(run.status)).length,
    succeeded: runs.filter((run) => run.status === 'BASARILI').length,
    attention: runs.filter((run) => attentionStatuses.has(run.status)).length,
    total: runs.length,
  }
}

export function ProjectOverviewPage() {
  const { projectUuid = '' } = useParams()
  const { t, i18n } = useTranslation()
  const [project, setProject] = useState<Project | null>(null)
  const [error, setError] = useState('')
  const [runs, setRuns] = useState<RunRecord[] | null>(null)
  const [runsError, setRunsError] = useState(false)
  const [reloadVersion, setReloadVersion] = useState(0)

  useEffect(() => {
    let active = true
    setProject(null)
    setRuns(null)
    setError('')
    setRunsError(false)
    void getProject(projectUuid).then((value) => { if (active) setProject(value) })
      .catch(() => { if (active) setError(t('common.loadError')) })
    void executionApi.listRuns(projectUuid)
      .then((value) => { if (active) setRuns(value) })
      .catch(() => { if (active) setRunsError(true) })
    return () => { active = false }
  }, [projectUuid, reloadVersion, t])

  const recentRuns = [...(runs ?? [])]
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
    .slice(0, 5)
  const summary = summarizeRuns(runs ?? [])
  const locale = i18n.resolvedLanguage === 'tr' || i18n.language.startsWith('tr') ? 'tr-TR' : 'en-US'
  const startTypeLabel = (startType: string) => {
    if (startType === 'ILK') return t('overview.initialStart')
    if (startType === 'MANUEL') return t('overview.manualStart')
    if (startType === 'ZAMANLANMIS') return t('overview.scheduledStart')
    return startType
  }

  return <section className="page-stack">
    <header className="page-header project-heading">
      <div><p className="eyebrow">{project?.code ?? t('common.loading')}</p><h1>{project?.name ?? t('overview.title')}</h1><p>{project?.description || t('overview.description')}</p></div>
      <div className="project-heading-actions">
        {project && <ExportProjectButton projectUuid={project.uuid} projectCode={project.code} />}
        <div className="status-panel"><span>{t('overview.status')}</span><strong><i />{project?.status === 'AKTIF' ? t('projects.active') : project?.status ?? t('common.loading')}</strong><small>v{project?.version ?? '—'}</small></div>
      </div>
    </header>
    {error ? <div className="error-banner action-banner" role="alert"><span>{error}</span><button type="button" onClick={() => setReloadVersion((value) => value + 1)}>{t('common.retry')}</button></div> : null}
    <div className="safety-banner"><ShieldCheck size={21} /><div><strong>{t('overview.safetyTitle')}</strong><p>{t('overview.safetyText')}</p></div></div>

    {runsError ? <div className="error-banner action-banner overview-activity-error" role="alert"><span>{t('overview.activityError')}</span><button type="button" onClick={() => setReloadVersion((value) => value + 1)}>{t('common.retry')}</button></div> : null}
    {runs && runs.length > 0 ? <section className="operations-overview" aria-labelledby="operations-overview-title">
      <div className="workflow-heading">
        <div><p className="eyebrow">{t('overview.operations')}</p><h2 id="operations-overview-title">{t('overview.operationsTitle')}</h2></div>
        <span>{t('overview.operationsHint')}</span>
      </div>
      <div className="operations-metrics">
        <article><Activity aria-hidden="true" /><span>{t('overview.running')}</span><strong>{summary.running}</strong></article>
        <article><CheckCircle2 aria-hidden="true" /><span>{t('overview.succeeded')}</span><strong>{summary.succeeded}</strong></article>
        <article className={summary.attention > 0 ? 'metric-attention' : ''}><CircleAlert aria-hidden="true" /><span>{t('overview.attention')}</span><strong>{summary.attention}</strong></article>
        <article><Gauge aria-hidden="true" /><span>{t('overview.totalRuns')}</span><strong>{summary.total}</strong></article>
      </div>
      <div className="recent-runs-panel">
        <div className="overview-table-wrap">
          <table className="overview-table">
            <thead><tr><th scope="col">{t('overview.run')}</th><th scope="col">{t('overview.startType')}</th><th scope="col">{t('overview.status')}</th><th scope="col">{t('overview.createdAt')}</th><th><span className="sr-only">{t('overview.open')}</span></th></tr></thead>
            <tbody>{recentRuns.map((run) => <tr key={run.runUuid}>
              <td><code title={run.runUuid}>{run.runUuid.slice(0, 8)}…</code></td>
              <td>{startTypeLabel(run.startType)}</td>
              <td><RunStatusBadge status={run.status} /></td>
              <td>{formatDate(run.createdAt, locale)}</td>
              <td><Link className="workflow-link" to={`runs/${run.runUuid}`}>{t('overview.viewRun')}<ArrowRight size={14} aria-hidden="true" /></Link></td>
            </tr>)}</tbody>
          </table>
        </div>
        <Link className="overview-history-link" to="runs">{t('overview.runHistory')}<ArrowRight size={15} aria-hidden="true" /></Link>
      </div>
    </section> : null}

    {runs?.length === 0 ? <div className="workflow-heading"><div><p className="eyebrow">{t('overview.gettingStarted')}</p><h2>{t('overview.gettingStartedTitle')}</h2></div><span>{t('overview.gettingStartedHint')}</span></div> : null}
    {runs === null && !runsError ? <div className="overview-loading" aria-label={t('common.loading')} /> : null}
    {runs?.length === 0 ?
    <div className="workflow-grid">{steps.map(({ path, key, text, icon: Icon, number }, index) => <Link to={path} className="workflow-card" key={path}>
      <div className="workflow-icon"><Icon size={21} /></div><span className="workflow-number">{number}</span><h3>{t(key)}</h3><p>{t(text)}</p><span className="workflow-link">{t('overview.open')}<ArrowRight size={15} /></span>{index < steps.length - 1 && <i className="workflow-connector" />}
    </Link>)}</div> : null}
  </section>
}
