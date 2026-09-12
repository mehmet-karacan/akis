import { ArrowLeft, Ban, CalendarClock, ChevronDown, ChevronRight, ListRestart, RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from '../operations/api'
import { CopyValue, Dialog, EmptyState, ErrorState, LoadingState, PageHeader, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate, redactSensitiveValues } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi, isExecutionDisabled } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { executionCodeLabel, useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import { buildRunStepTree, firstFailedPath, type RunStepNode } from './runTree'
import './execution.css'

function StepTreeItems({ nodes, selectedUuid, expanded, onSelect, onToggle, locale, expandLabel, collapseLabel }: { nodes: RunStepNode[]; selectedUuid?: string; expanded: Set<string>; onSelect: (uuid: string) => void; onToggle: (uuid: string) => void; locale: string; expandLabel: string; collapseLabel: string }) {
  return nodes.map((step) => { const hasChildren = step.children.length > 0; const open = expanded.has(step.uuid); return <li key={step.uuid} role="treeitem" aria-selected={selectedUuid === step.uuid} aria-expanded={hasChildren ? open : undefined}><div className="execution-tree-step-row">{hasChildren ? <button className="execution-tree-toggle" type="button" onClick={() => onToggle(step.uuid)} aria-label={open ? collapseLabel : expandLabel}>{open ? <ChevronDown /> : <ChevronRight />}</button> : <span className="execution-tree-spacer" />}<button type="button" onClick={() => onSelect(step.uuid)}><span><small>{step.ordinal}</small><strong>{step.name}</strong><em>{executionCodeLabel(step.type, locale)} · {step.code}</em></span><RunStatusBadge status={step.status} /></button></div>{hasChildren && open ? <ul role="group"><StepTreeItems nodes={step.children} selectedUuid={selectedUuid} expanded={expanded} onSelect={onSelect} onToggle={onToggle} locale={locale} expandLabel={expandLabel} collapseLabel={collapseLabel} /></ul> : null}</li> })
}

export function RunDetailPage() {
  const { runUuid = '' } = useParams(); const projectUuid = useCurrentProjectUuid()
  const { t, locale } = useExecutionI18n()
  const run = useRemoteData(() => executionApi.getRun(projectUuid, runUuid), [projectUuid, runUuid])
  const publication = useRemoteData(() => run.data?.publicationUuid ? operationsApi.getPublication(projectUuid, run.data.publicationUuid) : Promise.resolve(null), [projectUuid, run.data?.publicationUuid])
  const events = useRemoteData(() => executionApi.listEventPage(projectUuid, runUuid), [projectUuid, runUuid])
  const steps = useRemoteData(() => executionApi.listSteps(projectUuid, runUuid), [projectUuid, runUuid])
  const [selectedStepUuid, setSelectedStepUuid] = useState('')
  const [expandedSteps, setExpandedSteps] = useState<Set<string>>(new Set())
  const [evidenceTab, setEvidenceTab] = useState<'SUMMARY' | 'LOGS' | 'EVENTS'>('SUMMARY')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [actionError, setActionError] = useState('')
  const [executionDisabled, setExecutionDisabled] = useState(false)
  const cancelAvailability = run.data?.allowedActions?.find((action) => action.action === 'CANCEL')
  const canCancel = cancelAvailability?.allowed ?? false
  const stepTree = useMemo(() => buildRunStepTree(steps.data ?? []), [steps.data])
  useEffect(() => { if (!steps.data?.length || selectedStepUuid) return; const failurePath = firstFailedPath(stepTree); setSelectedStepUuid(failurePath.at(-1) ?? steps.data[0]!.uuid); setExpandedSteps(new Set(failurePath.slice(0, -1))) }, [selectedStepUuid, stepTree, steps.data])
  const selectedStep = steps.data?.find((step) => step.uuid === selectedStepUuid) ?? steps.data?.[0]

  const refresh = async () => {
    await Promise.all([run.reload(), steps.reload(), events.reload()])
  }
  const loadMoreEvents = async () => { if (!events.data?.hasMore || events.data.nextCursor == null) return; try { const next = await executionApi.listEventPage(projectUuid, runUuid, events.data.nextCursor); events.setData({ items: [...events.data.items, ...next.items], nextCursor: next.nextCursor, hasMore: next.hasMore }) } catch { /* The current page remains intact; the retry control stays available. */ } }

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
    <section className="ops-page execution-page">
      <Link className="ops-link execution-back" to={`/projects/${encodeURIComponent(projectUuid)}/operations`}><ArrowLeft aria-hidden="true" /> {t('backToRuns')}</Link>
      <PageHeader
        title={t('runDetail')}
        description={run.data ? `${t('runAttempt', { number: run.data.attemptNumber })} · ${executionCodeLabel(run.data.startType, locale)}` : t('runDetail')}
        actions={<>
          <button className="ops-button ops-button-secondary" type="button" onClick={() => void refresh()} disabled={run.loading || events.loading}><RefreshCw aria-hidden="true" /> {t('refresh')}</button>
          {canCancel ? <button className="ops-button ops-button-danger" type="button" onClick={() => setConfirmOpen(true)} disabled={executionDisabled}><Ban aria-hidden="true" /> {t('cancelRun')}</button> : null}
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
              <dt>{t('attempt')}</dt><dd>#{run.data.attemptNumber}</dd>
              <dt>{t('startType')}</dt><dd>{executionCodeLabel(run.data.startType, locale)}</dd>
              <dt>{t('createdAt')}</dt><dd>{formatDate(run.data.createdAt, locale)}</dd>
              <dt>{t('startedAt')}</dt><dd>{formatDate(run.data.startedAt, locale)}</dd>
              <dt>{t('finishedAt')}</dt><dd>{formatDate(run.data.finishedAt, locale)}</dd>
              <dt>{t('cancellationRequestedAt')}</dt><dd>{formatDate(run.data.cancellationRequestedAt, locale)}</dd>
              <dt>{t('environment')}</dt><dd>{publication.data ? `${publication.data.environmentCode} · ${executionCodeLabel(publication.data.environmentRisk, locale)}` : '—'}</dd>
              <dt>{t('runnableVersion')}</dt><dd>{publication.data ? `#${publication.data.publicationNumber}` : '—'}</dd>
              <dt>{t('targetSummary')}</dt><dd>{publication.data?.dependencySummary ?? '—'}</dd>
            </dl>
            {publication.error ? <div className="ops-alert ops-alert-error" role="alert">{t('pinnedContextUnavailable')} <button type="button" onClick={() => void publication.reload()}>{t('retry')}</button></div> : null}
            <details className="execution-technical-details"><summary>{t('technicalDetails')}</summary><dl className="ops-kv">
              <dt>{t('runUuid')}</dt><dd><CopyValue value={run.data.runUuid} /></dd>
              <dt>{t('jobRequestUuid')}</dt><dd><CopyValue value={run.data.jobRequestUuid} /></dd>
              <dt>{t('publicationUuid')}</dt><dd><CopyValue value={run.data.publicationUuid} /></dd>
              <dt>{t('releaseHash')}</dt><dd><CopyValue value={run.data.releaseHash} /></dd>
              <dt>{t('planHash')}</dt><dd><CopyValue value={run.data.planHash} /></dd>
            </dl></details>
            <div className="execution-action-note"><strong>{t('availableInterventions')}</strong><div className="execution-intervention-actions">{run.data.allowedActions?.filter((action) => ['START_NEW_ATTEMPT', 'RESUME'].includes(action.action)).map((action) => <button className="ops-button ops-button-secondary" type="button" key={action.action} disabled={!action.allowed} title={!action.allowed ? t('actionReason', { reason: action.reasonCode ?? 'UNAVAILABLE' }) : undefined}>{action.action === 'RESUME' ? t('resumeRun') : t('rerun')}</button>)}</div>{run.data.allowedActions?.some((action) => ['START_NEW_ATTEMPT', 'RESUME'].includes(action.action) && !action.allowed) ? <p>{t('retryUnsupported')}</p> : null}</div>
          </Panel>
          <Panel title={t('steps')} className="execution-steps-panel">
            {steps.loading ? <LoadingState /> : null}
            {!steps.loading && steps.error ? <ErrorState message={apiErrorMessage(steps.error, t('requestFailed'))} onRetry={() => void steps.reload()} /> : null}
            {!steps.loading && !steps.error && steps.data?.length === 0 ? <EmptyState>{t('emptySteps')}</EmptyState> : null}
            {!steps.loading && steps.data && steps.data.length > 0 ? <div className="execution-step-layout">
              <ul className="execution-step-tree" role="tree" aria-label={t('steps')}><StepTreeItems nodes={stepTree} selectedUuid={selectedStep?.uuid} expanded={expandedSteps} onSelect={setSelectedStepUuid} onToggle={(uuid) => setExpandedSteps((current) => { const next = new Set(current); if (next.has(uuid)) next.delete(uuid); else next.add(uuid); return next })} locale={locale} expandLabel={t('expandStep')} collapseLabel={t('collapseStep')} /></ul>
              {selectedStep ? <section className="execution-step-detail" aria-label={t('stepDetail')}><h3>{selectedStep.name}</h3><dl className="ops-kv">
                <dt>{t('status')}</dt><dd><RunStatusBadge status={selectedStep.status} /></dd>
                <dt>{t('type')}</dt><dd>{executionCodeLabel(selectedStep.type, locale)}</dd>
                <dt>{t('connectionRole')}</dt><dd>{executionCodeLabel(selectedStep.connectionRole, locale)}</dd>
                <dt>{t('risk')}</dt><dd>{executionCodeLabel(selectedStep.risk, locale)}</dd>
                <dt>{t('startedAt')}</dt><dd>{formatDate(selectedStep.startedAt, locale)}</dd>
                <dt>{t('finishedAt')}</dt><dd>{formatDate(selectedStep.finishedAt, locale)}</dd>
                <dt>{t('rowCount')}</dt><dd>{selectedStep.rowCount ?? '—'}</dd>
                <dt>{t('byteCount')}</dt><dd>{selectedStep.byteCount ?? '—'}</dd>
                <dt>{t('errorCode')}</dt><dd>{selectedStep.errorCode ?? '—'}</dd>
              </dl></section> : null}
            </div> : null}
          </Panel>
          <Panel title={t('evidence')} className="execution-events-panel">
            <div className="execution-evidence-tabs" role="tablist" aria-label={t('evidence')}>{(['SUMMARY', 'LOGS', 'EVENTS'] as const).map((tab) => <button type="button" role="tab" aria-selected={evidenceTab === tab} key={tab} onClick={() => setEvidenceTab(tab)}>{t(`tab_${tab}`)}</button>)}</div>
            {evidenceTab === 'SUMMARY' && selectedStep ? <section className="execution-evidence-summary"><h3>{selectedStep.name}</h3>{selectedStep.errorCode ? <div className="ops-alert ops-alert-error" role="alert"><strong>{selectedStep.errorCode}</strong><p>{t('errorMessageUnavailable')}</p></div> : null}<p>{t('metricScope')}</p><dl className="ops-kv"><dt>{t('rowCount')}</dt><dd>{selectedStep.rowCount ?? '—'}</dd><dt>{t('byteCount')}</dt><dd>{selectedStep.byteCount ?? '—'}</dd><dt>{t('startedAt')}</dt><dd>{formatDate(selectedStep.startedAt, locale)}</dd><dt>{t('finishedAt')}</dt><dd>{formatDate(selectedStep.finishedAt, locale)}</dd></dl></section> : null}
            {evidenceTab === 'LOGS' && <section className="execution-log-viewer"><header><strong>{t('operationalEventLog')}</strong><span>{t('eventLogScope')}</span></header>{events.loading ? <LoadingState /> : null}{!events.loading && events.error ? <ErrorState message={apiErrorMessage(events.error, t('requestFailed'))} onRetry={() => void events.reload()} /> : null}{!events.loading && !events.error && !events.data?.items.length ? <EmptyState>{t('noStepLog')}</EmptyState> : null}{events.data?.items.map((event) => <div className="execution-log-line" key={event.uuid}><time dateTime={event.eventTime}>{formatDate(event.eventTime, locale)}</time><span>INFO</span><strong>{executionCodeLabel(event.type, locale)}</strong><em>#{event.eventNumber}</em></div>)}{events.data?.hasMore ? <button className="ops-button ops-button-secondary" type="button" onClick={() => void loadMoreEvents()}>{t('loadMore')}</button> : null}</section>}
            {evidenceTab === 'EVENTS' && events.loading ? <LoadingState /> : null}
            {evidenceTab === 'EVENTS' && !events.loading && events.error ? <ErrorState message={apiErrorMessage(events.error, t('requestFailed'))} onRetry={() => void events.reload()} /> : null}
            {evidenceTab === 'EVENTS' && !events.loading && !events.error && events.data?.items.length === 0 ? <EmptyState>{t('emptyEvents')}</EmptyState> : null}
            {evidenceTab === 'EVENTS' && !events.loading && events.data && events.data.items.length > 0 ? (
              <><ol className="execution-timeline" aria-label={t('events')}>{events.data.items.map((event) => (
                <li key={event.uuid}>
                  <span className="execution-event-marker"><ListRestart aria-hidden="true" /></span>
                  <article>
                    <header><div><strong>{executionCodeLabel(event.type, locale)}</strong><span>#{event.eventNumber}</span></div><time dateTime={event.eventTime}><CalendarClock aria-hidden="true" />{formatDate(event.eventTime, locale)}</time></header>
                    {event.data == null || (typeof event.data === 'object' && Object.keys(event.data as object).length === 0)
                      ? <p className="ops-muted">{t('noEventData')}</p>
                      : <details><summary>{t('eventData')}</summary><pre>{JSON.stringify(redactSensitiveValues(event.data), null, 2)}</pre></details>}
                  </article>
                </li>
              ))}</ol>{events.data.hasMore ? <button className="ops-button ops-button-secondary" type="button" onClick={() => void loadMoreEvents()}>{t('loadMore')}</button> : null}</>
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
    </section>
  )
}
