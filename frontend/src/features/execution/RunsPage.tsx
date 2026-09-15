import { Activity, CheckCircle2, ChevronLeft, ChevronRight, Clock3, RefreshCw, Search, XCircle } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from '../operations/api'
import { EmptyState, ErrorState, LoadingState, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import { PageHeader } from '../../core/ui'
import { executionCodeLabel, useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import { FilterSection } from '../../core/ui/FilterSection'
import { SummaryStrip } from '../../core/ui/SummaryStrip'
import { RunDetailPage } from './RunDetailPage'
import { type RunSearchInput, type RunView } from './types'
import './execution.css'

const views: RunView[] = ['RECENT', 'ACTIVE', 'FAILED', 'HISTORY']
const activeStatuses = new Set(['BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT'])
const allowedSizes = [25, 50, 100]

function validView(value: string | null): RunView { return views.includes(value as RunView) ? value as RunView : 'RECENT' }
function positiveInt(value: string | null, fallback: number) { const parsed = Number(value); return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback }
function duration(startedAt: string | null, finishedAt: string | null, locale: string) {
  if (!startedAt) return '—'
  const milliseconds = (finishedAt ? Date.parse(finishedAt) : Date.now()) - Date.parse(startedAt)
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '—'
  const seconds = Math.floor(milliseconds / 1000)
  if (seconds < 60) return `${new Intl.NumberFormat(locale).format(seconds)} sn`
  const minutes = Math.floor(seconds / 60); const hours = Math.floor(minutes / 60)
  return hours ? `${hours} sa ${minutes % 60} dk` : `${minutes} dk`
}

export function RunsPage() {
  const projectUuid = useCurrentProjectUuid(); const [searchParams, setSearchParams] = useSearchParams(); const { t, locale } = useExecutionI18n()
  const view = validView(searchParams.get('view')); const page = positiveInt(searchParams.get('page'), 0); const requestedSize = positiveInt(searchParams.get('size'), 50); const size = allowedSizes.includes(requestedSize) ? requestedSize : 50
  const query = searchParams.get('query') ?? ''; const status = searchParams.get('statuses') ?? ''; const environment = searchParams.get('environment') ?? ''; const definitionType = searchParams.get('definitionType') ?? ''; const from = searchParams.get('from') ?? ''; const to = searchParams.get('to') ?? ''
  const selectedRunUuid = searchParams.get('run') ?? ''
  const [filterDraft, setFilterDraft] = useState({ view, status, environment, definitionType });
  const [queryDraft, setQueryDraft] = useState(query); const [fromDraft, setFromDraft] = useState(from); const [toDraft, setToDraft] = useState(to); const [live, setLive] = useState(false)
  const [refreshSeconds, setRefreshSeconds] = useState('15')
  const refreshInterval = Number(refreshSeconds)
  const validInterval = Number.isInteger(refreshInterval) && refreshInterval >= 1 && refreshInterval <= 86400
  const refreshing = useRef(false)
  const tr = locale.startsWith('tr')
  const searchInput: RunSearchInput = { view, query, statuses: status, environment, definitionType, from, to, page, size }
  const runs = useRemoteData(() => executionApi.searchRuns(projectUuid, searchInput), [projectUuid, view, query, status, environment, definitionType, from, to, page, size])
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const environmentCodes = useMemo(() => [...new Set((publications.data ?? []).map(item => item.environmentCode))].sort(), [publications.data])
  const refreshRuns = async () => {
    if (refreshing.current || runs.loading) return
    refreshing.current = true
    try { await runs.reload() } finally { refreshing.current = false }
  }
  useEffect(() => {
    if (!live || !validInterval) return
    let pending = false
    const timer = window.setInterval(() => {
      if (pending || refreshing.current || document.visibilityState !== 'visible') return
      pending = true
      refreshing.current = true
      void runs.reload().finally(() => { pending = false; refreshing.current = false })
    }, refreshInterval * 1000)
    return () => window.clearInterval(timer)
  }, [live, validInterval, refreshInterval, runs.reload])
  const updateFilters = (changes: Record<string, string>) => { const next = new URLSearchParams(searchParams); for (const [key, value] of Object.entries(changes)) { if (value) next.set(key, value); else next.delete(key) } if (!('page' in changes)) next.delete('page'); setSearchParams(next) }
  const openRun = (runUuid: string) => { const next = new URLSearchParams(searchParams); next.set('run', runUuid); setSearchParams(next) }
  const closeRun = () => { const next = new URLSearchParams(searchParams); next.delete('run'); setSearchParams(next, { replace: true }) }

  const totalPages = Math.max(1, Math.ceil((runs.data?.total ?? 0) / size))

  return <section className="ops-page execution-page">
    <PageHeader title={t('runs')} description={t('runsOperationalHelp')} />
    <FilterSection><form className="run-filters" onSubmit={(event) => { event.preventDefault(); updateFilters({ query: queryDraft, view: filterDraft.view, statuses: filterDraft.status, environment: filterDraft.environment, definitionType: filterDraft.definitionType }) }}>
      <fieldset className="run-view-filter"><legend>{tr ? 'Çalıştırma Filtresi' : 'Run Filter'}</legend><div className="run-view-options">{views.map(item => <label key={item}><input type="radio" name="run-view" value={item} checked={filterDraft.view === item} onChange={() => setFilterDraft(current => ({ ...current, view: item }))} /><span>{t(`view_${item}`)}</span></label>)}</div></fieldset>
      <label className="execution-search"><Search aria-hidden="true" /><span>{t('searchRuns')}</span><input value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} placeholder={t('searchRunsPlaceholder')} /></label>
      <label><span>{t('status')}</span><select aria-label={t('status')} value={filterDraft.status} onChange={(event) => setFilterDraft(current => ({ ...current, status: event.target.value }))}><option value="">{t('allStatuses')}</option><option value="CALISIYOR">{t('status_CALISIYOR')}</option><option value="BASARISIZ">{t('status_BASARISIZ')}</option><option value="BASARILI">{t('status_BASARILI')}</option><option value="SONUCU_BILINMIYOR">{t('status_SONUC_BELIRSIZ')}</option></select></label>
      <label><span>{t('environment')}</span><select aria-label={t('environment')} value={filterDraft.environment} onChange={(event) => setFilterDraft(current => ({ ...current, environment: event.target.value }))}><option value="">{t('allEnvironments')}</option>{environmentCodes.map((code) => <option key={code}>{code}</option>)}</select></label>
      <label><span>{t('objectType')}</span><select aria-label={t('objectType')} value={filterDraft.definitionType} onChange={(event) => setFilterDraft(current => ({ ...current, definitionType: event.target.value }))}><option value="">{t('allObjectTypes')}</option><option value="PROSEDUR">{t('procedure')}</option><option value="MAPPING">{t('mapping')}</option><option value="PAKET">{t('package')}</option></select></label>
      <div className="run-filter-actions"><button className="ops-button" type="submit">{t('applyFilters')}</button>
      {<button className="ops-button ops-button-secondary" type="button" onClick={() => { setFilterDraft({ view: 'RECENT', status: '', environment: '', definitionType: '' }); setQueryDraft(''); setFromDraft(''); setToDraft(''); updateFilters({ view: 'RECENT', query: '', statuses: '', environment: '', definitionType: '', from: '', to: '' }) }}>{t('clearFilters')}</button>}</div>
    </form>
    {view === 'HISTORY' && <form className="run-date-filter" onSubmit={(event) => { event.preventDefault(); if (fromDraft && toDraft) updateFilters({ from: new Date(fromDraft).toISOString(), to: new Date(toDraft).toISOString() }) }}><label>{t('from')}<input type="datetime-local" value={fromDraft ? fromDraft.slice(0, 16) : ''} onChange={(event) => setFromDraft(event.target.value)} /></label><label>{t('to')}<input type="datetime-local" value={toDraft ? toDraft.slice(0, 16) : ''} onChange={(event) => setToDraft(event.target.value)} /></label><button className="ops-button ops-button-secondary" disabled={!fromDraft || !toDraft}>{t('apply')}</button></form>}
    </FilterSection><SummaryStrip ariaLabel={t('runs')} items={[
      { label: locale.startsWith('tr') ? 'Toplam Çalıştırma' : 'Total Runs', value: runs.data?.total ?? 0, icon: <Activity />, tone: 'info' },
      { label: t('status_CALISIYOR'), value: runs.data?.items.filter((item) => activeStatuses.has(item.run.status)).length ?? 0, icon: <Clock3 />, tone: 'warning' },
      { label: t('status_BASARILI'), value: runs.data?.items.filter((item) => item.run.status === 'BASARILI').length ?? 0, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('status_BASARISIZ'), value: runs.data?.items.filter((item) => item.run.status === 'BASARISIZ').length ?? 0, icon: <XCircle />, tone: 'danger' },
    ]} />
    <Panel><header className="run-records-header"><div><h2>{tr ? 'Çalıştırma Listesi' : 'Run List'}</h2><p>{tr ? 'Çalıştırma sonuçları, süreler ve aktarılan satırlar.' : 'Execution results, durations and transferred rows.'}</p></div><div className="run-refresh-controls">
      <button className="ops-button ops-button-secondary" type="button" onClick={() => void refreshRuns()} disabled={runs.loading}><RefreshCw aria-hidden="true" />{t('refresh')}</button>
      <label className="run-refresh-interval"><span>{tr ? 'Aralık (sn)' : 'Interval (sec)'}</span><input type="number" min="1" max="86400" step="1" value={refreshSeconds} aria-invalid={!validInterval} onChange={event => setRefreshSeconds(event.target.value)} /></label>
      <label className="run-auto-refresh"><input type="checkbox" role="switch" checked={live} onChange={event => setLive(event.target.checked)} /><span>{tr ? 'Sürekli Yenile' : 'Auto Refresh'}</span></label>
      {!validInterval && <small role="alert">{tr ? '1–86400 arasında tam sayı girin.' : 'Enter a whole number between 1 and 86400.'}</small>}
    </div></header>{runs.loading && !runs.data && <LoadingState />}{Boolean(runs.error) && !runs.data && <ErrorState message={apiErrorMessage(runs.error, t('requestFailed'))} onRetry={() => void runs.reload()} />}{Boolean(runs.error) && runs.data && <div className="ops-alert ops-alert-error" role="alert">{t('refreshFailed')} <button type="button" onClick={() => void runs.reload()}>{t('retry')}</button></div>}{!runs.loading && !runs.error && runs.data?.items.length === 0 && <EmptyState>{t('emptyRuns')}</EmptyState>}
      {runs.data && runs.data.items.length > 0 && <div className="run-table-wrap"><table className="run-table"><thead><tr><th>{t('status')}</th><th>{t('object')}</th><th>{t('environment')}</th><th>{t('startedAt')}</th><th>{t('duration')}</th><th>{t('selectedRows')}</th><th>{t('insertedRows')}</th><th>{t('initiator')}</th><th>{t('actions')}</th></tr></thead><tbody>{runs.data.items.map((item) => <tr key={item.run.runUuid}><td><RunStatusBadge status={item.run.status} /></td><td><button className="run-object-button" type="button" onClick={() => openRun(item.run.runUuid)}><strong>{item.definitionName}</strong><small>{executionCodeLabel(item.definitionType, locale)} · {item.definitionCode}</small></button></td><td><strong>{item.environmentName}</strong><small>{item.environmentCode} · {executionCodeLabel(item.environmentRisk, locale)}</small></td><td>{formatDate(item.run.startedAt ?? item.run.createdAt, locale)}</td><td>{duration(item.run.startedAt, item.run.finishedAt, locale)}</td><td className="run-numeric">{item.selectedRows === null ? t('notRecorded') : new Intl.NumberFormat(locale).format(item.selectedRows)}</td><td className="run-numeric">{item.insertedRows === null ? t('notRecorded') : new Intl.NumberFormat(locale).format(item.insertedRows)}</td><td>{item.initiatorName}</td><td><button className="ops-link run-detail-button" type="button" onClick={() => openRun(item.run.runUuid)}>{t('viewDetails')}</button></td></tr>)}</tbody></table></div>}
      {runs.data && totalPages > 1 && <footer className="run-pagination"><label>{t('pageSize')}<select value={size} onChange={(event) => updateFilters({ size: event.target.value, page: '0' })}>{allowedSizes.map((value) => <option key={value}>{value}</option>)}</select></label><button type="button" disabled={page === 0} onClick={() => updateFilters({ page: String(page - 1) })} aria-label={t('previousPage')}><ChevronLeft /></button><span>{page + 1} / {totalPages}</span><button type="button" disabled={page + 1 >= totalPages} onClick={() => updateFilters({ page: String(page + 1) })} aria-label={t('nextPage')}><ChevronRight /></button></footer>}
    </Panel>
    {selectedRunUuid ? <RunDetailPage runUuidOverride={selectedRunUuid} panel onClose={closeRun} objectName={runs.data?.items.find((item) => item.run.runUuid === selectedRunUuid)?.definitionName} /> : null}
  </section>
}
