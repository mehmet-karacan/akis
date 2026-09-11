import { ChevronDown, ChevronRight, FileCode2, Play, Plus, Search } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { definitionsApi } from '../definitions/api'
import { operationsApi } from '../operations/api'
import type { Publication } from '../operations/types'
import { Dialog, EmptyState, ErrorState, Field, LoadingState, PageHeader, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { createIdempotencyKey, executionApi, isExecutionDisabled } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import { isRunnablePublication, type RunRecord } from './types'
import './execution.css'

export function RunsPage() {
  const { projectUuid = '' } = useParams()
  const navigate = useNavigate()
  const { t, locale } = useExecutionI18n()
  const runs = useRemoteData(() => executionApi.listRuns(projectUuid), [projectUuid])
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const definitions = useRemoteData(() => definitionsApi.listDefinitions(projectUuid), [projectUuid])
  const capabilities = useRemoteData(() => executionApi.getCapabilities(projectUuid), [projectUuid])
  const runnablePublications = useMemo(() => (publications.data ?? []).filter(isRunnablePublication), [publications.data])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [publicationUuid, setPublicationUuid] = useState('')
  const [idempotencyKey, setIdempotencyKey] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [executionDisabled, setExecutionDisabled] = useState(false)
  const [expandedObjects, setExpandedObjects] = useState<Set<string>>(new Set())
  const [query, setQuery] = useState('')
  const [visibleCount, setVisibleCount] = useState(50)
  const runtimeUnavailable = executionDisabled || capabilities.data?.runtime.runnable === false
  const runtimeUnavailableReason = capabilities.data?.runtime.acceptsManualRequests === false || executionDisabled ? 'requests' : 'worker'

  const definitionByUuid = useMemo(() => new Map((definitions.data ?? []).map((item) => [item.uuid, item])), [definitions.data])
  const publicationByUuid = useMemo(() => new Map((publications.data ?? []).map((item) => [item.uuid, item])), [publications.data])
  const runGroups = useMemo(() => {
    const grouped = new Map<string, { name: string; code: string; runs: RunRecord[] }>()
    for (const run of runs.data ?? []) {
      const publication = publicationByUuid.get(run.publicationUuid)
      const definition = publication ? definitionByUuid.get(publication.definitionUuid) : undefined
      const key = definition?.uuid ?? publication?.definitionUuid ?? 'unknown'
      const current = grouped.get(key) ?? { name: definition?.name ?? t('unnamedObject'), code: definition?.code ?? '—', runs: [] }
      current.runs.push(run); grouped.set(key, current)
    }
    const normalized = query.trim().toLocaleLowerCase(locale)
    return [...grouped.entries()].filter(([, item]) => !normalized || `${item.name} ${item.code}`.toLocaleLowerCase(locale).includes(normalized))
  }, [definitionByUuid, locale, publicationByUuid, query, runs.data, t])

  const openDialog = () => { setPublicationUuid(runnablePublications[0]?.uuid ?? ''); setIdempotencyKey(createIdempotencyKey()); setSubmitError(''); setDialogOpen(true) }
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!publicationUuid || !idempotencyKey) return
    setSubmitting(true); setSubmitError('')
    try { const run = await executionApi.startRun(projectUuid, publicationUuid, idempotencyKey); setDialogOpen(false); await navigate(`/projects/${encodeURIComponent(projectUuid)}/operations/runs/${run.runUuid}`) }
    catch (error) { if (isExecutionDisabled(error)) { setExecutionDisabled(true); setDialogOpen(false) } else setSubmitError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setSubmitting(false) }
  }
  const publicationLabel = (publication: Publication) => `${definitionByUuid.get(publication.definitionUuid)?.name ?? t('unnamedObject')} · #${publication.publicationNumber} · ${publication.environmentCode}`

  return <section className="ops-page execution-page">
    <PageHeader title={t('runs')} description={t('runsHelp')} actions={<button className="ops-button" type="button" onClick={openDialog} disabled={runtimeUnavailable || capabilities.loading || Boolean(capabilities.error) || publications.loading || runnablePublications.length === 0}><Plus aria-hidden="true" /> {t('startRun')}</button>} />
    {runtimeUnavailable && <ExecutionDisabledNotice reason={runtimeUnavailableReason} />}
    {!capabilities.loading && Boolean(capabilities.error) && <ErrorState message={t('capabilityUnavailable')} onRetry={() => void capabilities.reload()} />}
    {!publications.loading && Boolean(publications.error) && <ErrorState message={apiErrorMessage(publications.error, t('requestFailed'))} onRetry={() => void publications.reload()} />}
    {!publications.loading && !publications.error && runnablePublications.length === 0 && <div className="execution-guidance"><Play aria-hidden="true" /><span>{t('noActivePublication')}</span></div>}
    <label className="execution-search"><Search aria-hidden="true" /><span className="ops-visually-hidden">{t('searchRuns')}</span><input value={query} onChange={(event) => { setQuery(event.target.value); setVisibleCount(50) }} placeholder={t('searchRunsPlaceholder')} /></label>
    <Panel>
      {runs.loading && <LoadingState />}
      {!runs.loading && Boolean(runs.error) && <ErrorState message={apiErrorMessage(runs.error, t('requestFailed'))} onRetry={() => void runs.reload()} />}
      {!runs.loading && !runs.error && runs.data?.length === 0 && <EmptyState>{t('emptyRuns')}</EmptyState>}
      {!runs.loading && !runs.error && runGroups.length === 0 && runs.data && runs.data.length > 0 && <EmptyState>{t('noMatchingRuns')}</EmptyState>}
      {!runs.loading && !runs.error && runGroups.length > 0 && <ul className="execution-tree" role="tree" aria-label={t('runTree')}>
        {runGroups.slice(0, visibleCount).map(([uuid, group]) => { const open = expandedObjects.has(uuid); return <li key={uuid} role="treeitem" aria-expanded={open}>
          <button className="execution-object-row" type="button" onClick={() => setExpandedObjects((current) => { const next = new Set(current); if (next.has(uuid)) next.delete(uuid); else next.add(uuid); return next })}>{open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}<FileCode2 aria-hidden="true" /><span><strong>{group.name}</strong><small>{group.code} · {t('runCount').replace('{{count}}', String(group.runs.length))}</small></span></button>
          {open && <ul role="group">{group.runs.map((run) => <li key={run.runUuid} role="treeitem"><Link className="execution-run-row" to={`/projects/${encodeURIComponent(projectUuid)}/operations/runs/${run.runUuid}`}><span><strong>{formatDate(run.createdAt, locale)}</strong><small>#{run.attemptNumber} · {run.startType === 'ILK' ? t('startInitial') : run.startType}</small></span><RunStatusBadge status={run.status} /><ChevronRight aria-hidden="true" /></Link></li>)}</ul>}
        </li> })}
      </ul>}
      {runGroups.length > visibleCount && <button className="ops-button ops-button-secondary execution-load-more" type="button" onClick={() => setVisibleCount((count) => count + 50)}>{t('loadMore')}</button>}
    </Panel>
    {dialogOpen && <Dialog title={t('startRun')} onClose={() => !submitting && setDialogOpen(false)}><form className="ops-form" onSubmit={(event) => void submit(event)}>
      {submitError && <div className="ops-alert ops-alert-error" role="alert">{submitError}</div>}
      <Field label={t('publication')} hint={t('choosePublication')}><select value={publicationUuid} onChange={(event) => setPublicationUuid(event.target.value)} required>{runnablePublications.map((publication) => <option key={publication.uuid} value={publication.uuid}>{publicationLabel(publication)}</option>)}</select></Field>
      <div className="execution-idempotency-note">{t('idempotencyPrepared')}</div>
      <div className="ops-form-actions"><button className="ops-button ops-button-secondary" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</button><button className="ops-button" type="submit" disabled={submitting || !publicationUuid}>{submitting ? t('starting') : t('startRun')}</button></div>
    </form></Dialog>}
  </section>
}
