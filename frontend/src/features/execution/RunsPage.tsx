import { Button as AntActionButton } from '../../core/ui/Button'
import { DataGrid } from '../../core/ui/DataGrid'
import { Segmented } from 'antd'
import { Switch } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { Activity, CheckCircle2, ChevronLeft, ChevronRight, Clock3, RefreshCw, Search, XCircle } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from '../operations/api'
import { EmptyState, ErrorState, LoadingState, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import { exactCount } from './types'
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
      <fieldset className="run-view-filter"><legend>{tr ? 'Çalıştırma Filtresi' : 'Run Filter'}</legend><Segmented aria-label={tr ? 'Çalıştırma Filtresi' : 'Run Filter'} options={views.map(value => ({ value, label: t(`view_${value}`) }))} value={filterDraft.view} onChange={value => setFilterDraft(current => ({ ...current, view: value }))} /></fieldset>
      <label className="execution-search"><Search aria-hidden="true" /><span>{t('searchRuns')}</span><AntInput value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} placeholder={t('searchRunsPlaceholder')} /></label>
      <label><span>{t('status')}</span><FormSelect aria-label={t('status')} value={filterDraft.status} onChange={(event) => setFilterDraft(current => ({ ...current, status: event.target.value }))}><option value="">{t('allStatuses')}</option><option value="CALISIYOR">{t('status_CALISIYOR')}</option><option value="BASARISIZ">{t('status_BASARISIZ')}</option><option value="BASARILI">{t('status_BASARILI')}</option><option value="SONUCU_BILINMIYOR">{t('status_SONUC_BELIRSIZ')}</option></FormSelect></label>
      <label><span>{t('environment')}</span><FormSelect aria-label={t('environment')} value={filterDraft.environment} onChange={(event) => setFilterDraft(current => ({ ...current, environment: event.target.value }))}><option value="">{t('allEnvironments')}</option>{environmentCodes.map((code) => <option key={code}>{code}</option>)}</FormSelect></label>
      <label><span>{t('objectType')}</span><FormSelect aria-label={t('objectType')} value={filterDraft.definitionType} onChange={(event) => setFilterDraft(current => ({ ...current, definitionType: event.target.value }))}><option value="">{t('allObjectTypes')}</option><option value="PROSEDUR">{t('procedure')}</option><option value="MAPPING">{t('mapping')}</option><option value="PAKET">{t('package')}</option></FormSelect></label>
      <div className="run-filter-actions"><AntActionButton tone="primary" icon={<Search size={16} />} className="ops-button" type="submit">{t('applyFilters')}</AntActionButton>
      {<AntActionButton tone="secondary" icon={<XCircle size={16} />} className="ops-button ops-button-secondary" type="button" onClick={() => { setFilterDraft({ view: 'RECENT', status: '', environment: '', definitionType: '' }); setQueryDraft(''); setFromDraft(''); setToDraft(''); updateFilters({ view: 'RECENT', query: '', statuses: '', environment: '', definitionType: '', from: '', to: '' }) }}>{t('clearFilters')}</AntActionButton>}</div>
    </form>
    {view === 'HISTORY' && <form className="run-date-filter" onSubmit={(event) => { event.preventDefault(); if (fromDraft && toDraft) updateFilters({ from: new Date(fromDraft).toISOString(), to: new Date(toDraft).toISOString() }) }}><label>{t('from')}<AntInput type="datetime-local" value={fromDraft ? fromDraft.slice(0, 16) : ''} onChange={(event) => setFromDraft(event.target.value)} /></label><label>{t('to')}<AntInput type="datetime-local" value={toDraft ? toDraft.slice(0, 16) : ''} onChange={(event) => setToDraft(event.target.value)} /></label><AntActionButton type="submit" tone="primary" className="ops-button ops-button-secondary" disabled={!fromDraft || !toDraft}>{t('apply')}</AntActionButton></form>}
    </FilterSection><SummaryStrip ariaLabel={t('runs')} items={[
      { label: locale.startsWith('tr') ? 'Toplam Çalıştırma' : 'Total Runs', value: runs.data?.total ?? 0, icon: <Activity />, tone: 'info' },
      { label: t('status_CALISIYOR'), value: runs.data?.items.filter((item) => activeStatuses.has(item.run.status)).length ?? 0, icon: <Clock3 />, tone: 'warning' },
      { label: t('status_BASARILI'), value: runs.data?.items.filter((item) => item.run.status === 'BASARILI').length ?? 0, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('status_BASARISIZ'), value: runs.data?.items.filter((item) => item.run.status === 'BASARISIZ').length ?? 0, icon: <XCircle />, tone: 'danger' },
    ]} />
    <Panel><header className="run-records-header"><div><h2>{tr ? 'Çalıştırma Listesi' : 'Run List'}</h2><p>{tr ? 'Çalıştırma sonuçları, süreler ve aktarılan satırlar.' : 'Execution results, durations and transferred rows.'}</p></div><div className="run-refresh-controls">
      <AntActionButton tone="ghost" className="ops-button ops-button-secondary" type="button" onClick={() => void refreshRuns()} disabled={runs.loading}><RefreshCw aria-hidden="true" />{t('refresh')}</AntActionButton>
      <label className="run-refresh-interval"><span>{tr ? 'Aralık (sn)' : 'Interval (sec)'}</span><AntInput type="number" min="1" max="86400" step="1" value={refreshSeconds} aria-invalid={!validInterval} onChange={event => setRefreshSeconds(event.target.value)} /></label>
      <label className="run-auto-refresh"><Switch checked={live} onChange={setLive} /><span>{tr ? 'Sürekli Yenile' : 'Auto Refresh'}</span></label>
      {!validInterval && <small role="alert">{tr ? '1–86400 arasında tam sayı girin.' : 'Enter a whole number between 1 and 86400.'}</small>}
    </div></header>{runs.loading && !runs.data && <LoadingState />}{Boolean(runs.error) && !runs.data && <ErrorState message={apiErrorMessage(runs.error, t('requestFailed'))} onRetry={() => void runs.reload()} />}{Boolean(runs.error) && runs.data && <div className="ops-alert ops-alert-error" role="alert">{t('refreshFailed')} <AntActionButton tone="ghost" type="button" onClick={() => void runs.reload()}>{t('retry')}</AntActionButton></div>}{!runs.loading && !runs.error && runs.data?.items.length === 0 && <EmptyState>{t('emptyRuns')}</EmptyState>}
      {runs.data && runs.data.items.length > 0 && <div className="run-table-wrap"><DataGrid className="run-table"><thead><tr><th>{t('status')}</th><th>{t('object')}</th><th>{t('environment')}</th><th>{t('startedAt')}</th><th>{t('duration')}</th><th>{t('selectedRows')}</th><th>{t('insertedRows')}</th><th>{t('initiator')}</th><th>{t('actions')}</th></tr></thead><tbody>{runs.data.items.map((item) => <tr key={item.run.runUuid}><td><RunStatusBadge status={item.run.status} /></td><td><AntActionButton tone="ghost" className="run-object-button" type="button" onClick={() => openRun(item.run.runUuid)}><strong>{item.definitionName}</strong><small>{executionCodeLabel(item.definitionType, locale)} · {item.definitionCode}</small></AntActionButton></td><td><strong>{item.environmentName}</strong><small>{item.environmentCode} · {executionCodeLabel(item.environmentRisk, locale)}</small></td><td>{formatDate(item.run.startedAt ?? item.run.createdAt, locale)}</td><td>{duration(item.run.startedAt, item.run.finishedAt, locale)}</td><td className="run-numeric">{(exactCount(item.selectedRowsExact) ?? (item.selectedRows == null ? null : BigInt(item.selectedRows)))?.toLocaleString(locale) ?? t('notRecorded')}</td><td className="run-numeric">{(exactCount(item.insertedRowsExact) ?? (item.insertedRows == null ? null : BigInt(item.insertedRows)))?.toLocaleString(locale) ?? t('notRecorded')}</td><td>{item.initiatorName}</td><td><AntActionButton tone="ghost" className="ops-link run-detail-button" type="button" onClick={() => openRun(item.run.runUuid)}>{t('viewDetails')}</AntActionButton></td></tr>)}</tbody></DataGrid></div>}
      {runs.data && totalPages > 1 && <footer className="run-pagination"><label>{t('pageSize')}<FormSelect value={size} onChange={(event) => updateFilters({ size: event.target.value, page: '0' })}>{allowedSizes.map((value) => <option key={value}>{value}</option>)}</FormSelect></label><AntActionButton tone="ghost" type="button" disabled={page === 0} onClick={() => updateFilters({ page: String(page - 1) })} aria-label={t('previousPage')}><ChevronLeft /></AntActionButton><span>{page + 1} / {totalPages}</span><AntActionButton tone="ghost" type="button" disabled={page + 1 >= totalPages} onClick={() => updateFilters({ page: String(page + 1) })} aria-label={t('nextPage')}><ChevronRight /></AntActionButton></footer>}
    </Panel>
    {selectedRunUuid ? <RunDetailPage runUuidOverride={selectedRunUuid} panel onClose={closeRun} objectName={runs.data?.items.find((item) => item.run.runUuid === selectedRunUuid)?.definitionName} /> : null}
  </section>
}
