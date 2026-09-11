import { ArrowLeft, Ban, CalendarClock, ListRestart, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CopyValue, Dialog, EmptyState, ErrorState, LoadingState, PageHeader, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate, redactSensitiveValues } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi, isExecutionDisabled } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import './execution.css'

export function RunDetailPage() {
  const { projectUuid = '', runUuid = '' } = useParams()
  const { t, locale } = useExecutionI18n()
  const run = useRemoteData(() => executionApi.getRun(projectUuid, runUuid), [projectUuid, runUuid])
  const events = useRemoteData(() => executionApi.listEvents(projectUuid, runUuid), [projectUuid, runUuid])
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [actionError, setActionError] = useState('')
  const [executionDisabled, setExecutionDisabled] = useState(false)

  const refresh = async () => {
    await Promise.all([run.reload(), events.reload()])
  }

  const cancel = async () => {
    setCancelling(true)
    setActionError('')
    try {
      run.setData(await executionApi.cancelRun(projectUuid, runUuid))
      setConfirmOpen(false)
      await events.reload()
    } catch (error) {
      if (isExecutionDisabled(error)) {
        setExecutionDisabled(true)
        setConfirmOpen(false)
      } else {
        setActionError(apiErrorMessage(error, t('requestFailed')))
      }
    } finally {
      setCancelling(false)
    }
  }

  return (
    <main className="ops-page execution-page">
      <Link className="ops-link execution-back" to={`/projects/${encodeURIComponent(projectUuid)}/runs`}><ArrowLeft aria-hidden="true" /> {t('backToRuns')}</Link>
      <PageHeader
        title={t('runDetail')}
        description={run.data ? `${run.data.runUuid} · ${run.data.startType}` : runUuid}
        actions={<>
          <button className="ops-button ops-button-secondary" type="button" onClick={() => void refresh()} disabled={run.loading || events.loading}><RefreshCw aria-hidden="true" /> {t('refresh')}</button>
          {run.data?.status === 'BEKLIYOR' ? <button className="ops-button ops-button-danger" type="button" onClick={() => setConfirmOpen(true)} disabled={executionDisabled}><Ban aria-hidden="true" /> {t('cancelRun')}</button> : null}
        </>}
      />
      {executionDisabled ? <ExecutionDisabledNotice /> : null}
      {actionError ? <div className="ops-alert ops-alert-error" role="alert">{actionError}</div> : null}

      {run.loading ? <Panel><LoadingState /></Panel> : null}
      {!run.loading && run.error ? <Panel><ErrorState message={apiErrorMessage(run.error, t('requestFailed'))} onRetry={() => void run.reload()} /></Panel> : null}
      {!run.loading && run.data ? (
        <div className="execution-detail-grid">
          <Panel title={t('runContext')}>
            <dl className="ops-kv">
              <dt>{t('status')}</dt><dd><RunStatusBadge status={run.data.status} /></dd>
              <dt>{t('runUuid')}</dt><dd><CopyValue value={run.data.runUuid} /></dd>
              <dt>{t('jobRequestUuid')}</dt><dd><CopyValue value={run.data.jobRequestUuid} /></dd>
              <dt>{t('publicationUuid')}</dt><dd><CopyValue value={run.data.publicationUuid} /></dd>
              <dt>{t('attempt')}</dt><dd>#{run.data.attemptNumber}</dd>
              <dt>{t('startType')}</dt><dd>{run.data.startType}</dd>
              <dt>{t('planHash')}</dt><dd><CopyValue value={run.data.planHash} /></dd>
              <dt>{t('createdAt')}</dt><dd>{formatDate(run.data.createdAt, locale)}</dd>
              <dt>{t('startedAt')}</dt><dd>{formatDate(run.data.startedAt, locale)}</dd>
              <dt>{t('finishedAt')}</dt><dd>{formatDate(run.data.finishedAt, locale)}</dd>
              <dt>{t('cancellationRequestedAt')}</dt><dd>{formatDate(run.data.cancellationRequestedAt, locale)}</dd>
            </dl>
          </Panel>
          <Panel title={t('events')} className="execution-events-panel">
            {events.loading ? <LoadingState /> : null}
            {!events.loading && events.error ? <ErrorState message={apiErrorMessage(events.error, t('requestFailed'))} onRetry={() => void events.reload()} /> : null}
            {!events.loading && !events.error && events.data?.length === 0 ? <EmptyState>{t('emptyEvents')}</EmptyState> : null}
            {!events.loading && events.data && events.data.length > 0 ? (
              <ol className="execution-timeline">{events.data.map((event) => (
                <li key={event.uuid}>
                  <span className="execution-event-marker"><ListRestart aria-hidden="true" /></span>
                  <article>
                    <header><div><strong>{event.type}</strong><span>#{event.eventNumber}</span></div><time dateTime={event.eventTime}><CalendarClock aria-hidden="true" />{formatDate(event.eventTime, locale)}</time></header>
                    {event.data == null || (typeof event.data === 'object' && Object.keys(event.data as object).length === 0)
                      ? <p className="ops-muted">{t('noEventData')}</p>
                      : <details><summary>{t('eventData')}</summary><pre>{JSON.stringify(redactSensitiveValues(event.data), null, 2)}</pre></details>}
                  </article>
                </li>
              ))}</ol>
            ) : null}
          </Panel>
        </div>
      ) : null}

      {confirmOpen ? (
        <Dialog title={t('confirmCancel')} onClose={() => !cancelling && setConfirmOpen(false)}>
          <p className="ops-muted">{t('confirmCancelHelp')}</p>
          <div className="ops-form-actions execution-confirm-actions">
            <button className="ops-button ops-button-secondary" type="button" onClick={() => setConfirmOpen(false)} disabled={cancelling}>{t('keepRun')}</button>
            <button className="ops-button ops-button-danger" type="button" onClick={() => void cancel()} disabled={cancelling}>{cancelling ? t('cancelling') : t('confirm')}</button>
          </div>
        </Dialog>
      ) : null}
    </main>
  )
}
