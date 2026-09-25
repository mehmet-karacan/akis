import { Button as AntActionButton } from '../../core/ui/Button'
import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput, Tag } from 'antd'
import { Activity, ArrowDownToLine, CheckCircle2, Clock3, Database, ListRestart, LoaderCircle, PencilLine, PlayCircle, RefreshCw, Repeat2, Search, Timer, Trash2, XCircle } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from '../operations/api'
import { apiErrorMessage } from '../operations/utils'
import { formatOperationalDateTime, formatOperationalDuration } from '../../core/i18n/formatters'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import { exactCount } from './types'
import { AsyncState, PageHeader, RecordActionButton } from '../../core/ui'
import { connectionStatusTagStyles } from '../connections/presentation'
import { runStatusPresentation } from './RunStatusBadge'
import { executionCodeLabel, useExecutionI18n } from './i18n'
import { FilterSection } from '../../core/ui/FilterSection'
import { SummaryStrip } from '../../core/ui/SummaryStrip'
import { RunDetailPage } from './RunDetailPage'
import { type RunSearchInput, type RunView } from './types'
import './execution.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

const RUN_PAGE_SIZE = 50
const filterStatuses = ['BEKLIYOR', 'CALISIYOR', 'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI', 'BASARILI', 'BASARISIZ', 'SONUCU_BILINMIYOR', 'IPTAL'] as const

function toLocalDateTimeInput(value: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
}
function toApiDateTime(value: string) { return value ? new Date(value).toISOString() : '' }

export function RunsPage() {
  const projectUuid = useCurrentProjectUuid(); const [searchParams, setSearchParams] = useSearchParams(); const { t, locale } = useExecutionI18n()
  const view: RunView = 'RECENT'; const size = RUN_PAGE_SIZE
  const query = searchParams.get('query') ?? ''; const status = searchParams.get('statuses') ?? ''; const environment = searchParams.get('environment') ?? ''; const definitionType = searchParams.get('definitionType') ?? ''; const from = searchParams.get('from') ?? ''; const to = searchParams.get('to') ?? ''
  const selectedRunUuid = searchParams.get('run') ?? ''
  const triggerParam = searchParams.get('trigger') ?? ''
  const [filterDraft, setFilterDraft] = useState({ status, environment, definitionType, trigger: triggerParam })
  const [queryDraft, setQueryDraft] = useState(query); const [fromDraft, setFromDraft] = useState(() => toLocalDateTimeInput(from)); const [toDraft, setToDraft] = useState(() => toLocalDateTimeInput(to)); const [live, setLive] = useState(false)
  const [refreshSeconds, setRefreshSeconds] = useState('15')
  const refreshInterval = Number(refreshSeconds)
  const validInterval = Number.isInteger(refreshInterval) && refreshInterval >= 1 && refreshInterval <= 86400
  const validDateRange = !fromDraft || !toDraft || Date.parse(fromDraft) <= Date.parse(toDraft)
  const refreshing = useRef(false)
  const tr = locale.startsWith('tr')
  const scheduled = triggerParam === 'SCHEDULED' ? true : triggerParam === 'MANUAL' ? false : undefined
  const searchInput: RunSearchInput = { view, query, statuses: status, environment, definitionType, from, to, page: 0, size, scheduled }
  const runs = useRemoteData(() => executionApi.searchRuns(projectUuid, searchInput), [projectUuid, view, query, status, environment, definitionType, from, to, scheduled])
  const [additionalRuns, setAdditionalRuns] = useState<NonNullable<typeof runs.data>['items']>([])
  const [nextPage, setNextPage] = useState(1)
  const [loadingMore, setLoadingMore] = useState(false)
  const recordsScrollRoot = useRef<HTMLElement | null>(null)
  const scrollSentinel = useRef<HTMLDivElement | null>(null)
  const overview = useRemoteData(() => executionApi.getOverview(projectUuid), [projectUuid])
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const environmentCodes = useMemo(() => [...new Set((publications.data ?? []).map(item => item.environmentCode))].sort(), [publications.data])
  const refreshRuns = async () => {
    if (refreshing.current || runs.loading) return
    refreshing.current = true
    try { await Promise.all([runs.reload(), overview.reload()]) } finally { refreshing.current = false }
  }
  useEffect(() => {
    if (!live || !validInterval) return
    let pending = false
    const timer = window.setInterval(() => {
      if (pending || refreshing.current || document.visibilityState !== 'visible') return
      pending = true
      refreshing.current = true
      void Promise.all([runs.reload(), overview.reload()]).finally(() => { pending = false; refreshing.current = false })
    }, refreshInterval * 1000)
    return () => window.clearInterval(timer)
  }, [live, validInterval, refreshInterval, runs.reload])
  const updateFilters = (changes: Record<string, string>) => { const next = new URLSearchParams(searchParams); for (const [key, value] of Object.entries(changes)) { if (value) next.set(key, value); else next.delete(key) } next.delete('page'); next.delete('size'); setSearchParams(next) }
  const openRun = (runUuid: string) => { const next = new URLSearchParams(searchParams); next.set('run', runUuid); setSearchParams(next) }
  const closeRun = () => { const next = new URLSearchParams(searchParams); next.delete('run'); setSearchParams(next, { replace: true }) }

  const loadedRuns = useMemo(() => [...(runs.data?.items ?? []), ...additionalRuns], [runs.data?.items, additionalRuns])
  const totalRuns = runs.data?.total ?? 0
  const hasMore = loadedRuns.length < totalRuns
  useEffect(() => { setAdditionalRuns([]); setNextPage(1) }, [projectUuid, view, query, status, environment, definitionType, from, to, scheduled])
  const loadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return
    setLoadingMore(true)
    try {
      const pageData = await executionApi.searchRuns(projectUuid, { ...searchInput, page: nextPage })
      setAdditionalRuns(current => { const known = new Set(current.map(item => item.run.runUuid)); (runs.data?.items ?? []).forEach(item => known.add(item.run.runUuid)); return [...current, ...pageData.items.filter(item => !known.has(item.run.runUuid))] })
      setNextPage(value => value + 1)
    } finally { setLoadingMore(false) }
  }, [loadingMore, hasMore, projectUuid, searchInput, nextPage, runs.data?.items])
  useEffect(() => {
    const node = scrollSentinel.current
    const root = recordsScrollRoot.current
    if (!node || !root || !hasMore || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) void loadMore()
    }, { root, rootMargin: '240px 0px' })
    observer.observe(node)
    return () => observer.disconnect()
  }, [hasMore, loadMore])

  const statusTag = (runStatus: string) => { const presentation = runStatusPresentation(runStatus, t); return <Tag className={`connection-status-tag connection-status-tag--${presentation.tone} run-status run-status--${runStatus.toLowerCase()}`} style={connectionStatusTagStyles[presentation.tone]} icon={presentation.icon}><span className="connection-status-tag-label">{presentation.label}</span></Tag> }
  const applyFilters = () => { if (validDateRange) updateFilters({ view: '', query: queryDraft, statuses: filterDraft.status, environment: filterDraft.environment, definitionType: filterDraft.definitionType, from: toApiDateTime(fromDraft), to: toApiDateTime(toDraft), trigger: filterDraft.trigger }) }
  const clearFilters = () => { setFilterDraft({ status: '', environment: '', definitionType: '', trigger: '' }); setQueryDraft(''); setFromDraft(''); setToDraft(''); updateFilters({ view: '', query: '', statuses: '', environment: '', definitionType: '', from: '', to: '', trigger: '' }) }
  const refreshControls = <div className="run-refresh-controls">
    <AntActionButton tone="ghost" className="ops-button ops-button-secondary" type="button" onClick={() => void refreshRuns()} disabled={runs.loading}><RefreshCw aria-hidden="true" />{t('refresh')}</AntActionButton>
    <label className="run-refresh-interval" title={tr ? 'Otomatik yenileme aralığı' : 'Auto-refresh interval'}><Timer aria-hidden="true" /><AntInput aria-label={tr ? 'Yenileme aralığı saniye' : 'Refresh interval seconds'} type="number" min="1" max="86400" step="1" value={refreshSeconds} aria-invalid={!validInterval} onChange={event => setRefreshSeconds(event.target.value)} /><span>{tr ? 'sn' : 'sec'}</span></label>
    <AntActionButton tone={live ? 'primary' : 'ghost'} className="run-auto-refresh-button" type="button" icon={<Repeat2 aria-hidden="true" />} aria-pressed={live} aria-label={tr ? 'Sürekli yenilemeyi aç veya kapat' : 'Toggle auto refresh'} title={tr ? `Sürekli yenileme: ${live ? 'Açık' : 'Kapalı'}` : `Auto refresh: ${live ? 'On' : 'Off'}`} onClick={() => setLive(current => !current)} />
    {!validInterval && <small role="alert">{tr ? '1-86400 arasında tam sayı girin.' : 'Enter a whole number between 1 and 86400.'}</small>}
  </div>
  const count = (exact: unknown, plain: number | string | null | undefined) => (exactCount(exact as Parameters<typeof exactCount>[0]) ?? (plain == null ? null : BigInt(plain)))?.toLocaleString(locale) ?? t('notRecorded')

  return <section className="page-stack connections-page execution-page">
    <section className="connection-management-panel"><PageHeader icon={<PlayCircle />} eyebrow={tr ? 'OPERASYON' : 'OPERATIONS'} title={t('runs')} description={t('runsOperationalHelp')} />
    <FilterSection><form className="run-filters" onSubmit={(event) => { event.preventDefault(); applyFilters() }}>
      <label className="execution-search"><Search aria-hidden="true" /><span>{t('searchRuns')}</span><AntInput value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} placeholder={t('searchRunsPlaceholder')} /></label>
      <label><span>{t('status')}</span><FormSelect aria-label={t('status')} value={filterDraft.status} onChange={(event) => setFilterDraft(current => ({ ...current, status: event.target.value }))}><option value="">{t('allStatuses')}</option>{filterStatuses.map(item => <option key={item} value={item}>{t(item === 'SONUCU_BILINMIYOR' ? 'status_SONUC_BELIRSIZ' : `status_${item}` as Parameters<typeof t>[0])}</option>)}</FormSelect></label>
      <label><span>{t('environment')}</span><FormSelect aria-label={t('environment')} value={filterDraft.environment} onChange={(event) => setFilterDraft(current => ({ ...current, environment: event.target.value }))}><option value="">{t('allEnvironments')}</option>{environmentCodes.map((code) => <option key={code}>{code}</option>)}</FormSelect></label>
      <label><span>{t('objectType')}</span><FormSelect aria-label={t('objectType')} value={filterDraft.definitionType} onChange={(event) => setFilterDraft(current => ({ ...current, definitionType: event.target.value }))}><option value="">{t('allObjectTypes')}</option><option value="PROSEDUR">{executionCodeLabel('PROSEDUR', locale)}</option><option value="MAPPING">{executionCodeLabel('MAPPING', locale)}</option><option value="PAKET">{executionCodeLabel('PAKET', locale)}</option><option value="VARIABLE">{executionCodeLabel('VARIABLE', locale)}</option><option value="SEQUENCE">{executionCodeLabel('SEQUENCE', locale)}</option><option value="KNOWLEDGE_MODULE">{executionCodeLabel('KNOWLEDGE_MODULE', locale)}</option></FormSelect></label>
      <label><span>{t('trigger')}</span><FormSelect aria-label={t('trigger')} value={filterDraft.trigger} onChange={(event) => setFilterDraft(current => ({ ...current, trigger: event.target.value }))}><option value="">{tr ? 'Tümü' : 'All'}</option><option value="MANUAL">{tr ? 'Manuel' : 'Manual'}</option><option value="SCHEDULED">{tr ? 'Otomatik' : 'Automatic'}</option></FormSelect></label>
      <label><span>{tr ? 'Başlangıç Zamanı' : 'Start Time'}</span><AntInput aria-label={tr ? 'Başlangıç Zamanı' : 'Start Time'} type="datetime-local" step="1" value={fromDraft} onChange={(event) => setFromDraft(event.target.value)} /></label>
      <label><span>{tr ? 'Bitiş Zamanı' : 'End Time'}</span><AntInput aria-label={tr ? 'Bitiş Zamanı' : 'End Time'} type="datetime-local" step="1" value={toDraft} onChange={(event) => setToDraft(event.target.value)} /></label>
      <div className="run-filter-actions"><AntActionButton tone="primary" icon={<Search size={16} />} className="ops-button" type="submit" disabled={!validDateRange}>{t('applyFilters')}</AntActionButton>
      <AntActionButton tone="secondary" icon={<XCircle size={16} />} className="ops-button ops-button-secondary" type="button" onClick={clearFilters}>{t('clearFilters')}</AntActionButton></div>
      {!validDateRange && <small className="run-filter-error" role="alert">{tr ? 'Bitiş zamanı başlangıç zamanından önce olamaz.' : 'End time cannot be earlier than start time.'}</small>}
    </form><p className="form-note">{t(`scope_${view}` as Parameters<typeof t>[0])}</p></FilterSection></section>
    <section className="run-summary-stack">
      <SummaryStrip ariaLabel={tr ? 'Çalıştırma özeti' : 'Run summary'} items={[
        { label: tr ? 'Çalışan' : 'Running', value: overview.data?.activeRuns ?? '—', icon: <Clock3 />, tone: 'warning' },
        { label: tr ? 'Bekleyen' : 'Queued', value: overview.data?.queuedRuns ?? '—', icon: <ListRestart />, tone: 'neutral' },
        { label: t('status_BASARISIZ'), value: overview.data?.failedRuns ?? '—', icon: <XCircle />, tone: 'danger' },
        { label: t('status_BASARILI'), value: overview.data?.succeededRuns ?? '—', icon: <CheckCircle2 />, tone: 'success' },
        { label: tr ? 'Toplam' : 'Total', value: overview.data?.totalRuns ?? '—', icon: <Activity />, tone: 'info' },
        { label: t('selectedRows'), value: count(overview.data?.selectedRowsExact, null), icon: <Database />, tone: 'info' },
        { label: t('insertedRows'), value: count(overview.data?.insertedRowsExact, null), icon: <ArrowDownToLine />, tone: 'teal' },
        { label: tr ? 'Güncellenen' : 'Updated', value: count(overview.data?.updatedRowsExact, null), icon: <PencilLine />, tone: 'warning' },
        { label: tr ? 'Silinen' : 'Deleted', value: count(overview.data?.deletedRowsExact, null), icon: <Trash2 />, tone: 'danger' },
      ]} />
    </section>
    <section ref={recordsScrollRoot} className="connections-records">
      {Boolean(runs.error) && runs.data && <div className="error-banner" role="alert">{t('refreshFailed')} <AntActionButton tone="ghost" type="button" onClick={() => void runs.reload()}>{t('retry')}</AntActionButton></div>}
      {runs.loading && !runs.data ? <AsyncState state="loading" title={t('loading')} /> : Boolean(runs.error) && !runs.data ? <AsyncState state="error" title={apiErrorMessage(runs.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void runs.reload()} /> : !runs.data || loadedRuns.length === 0 ? <AsyncState state="empty" title={t('emptyRuns')} action={refreshControls} /> : <DataGrid viewControls={false} collectionTitle={tr ? 'Çalıştırma Listesi' : 'Run List'} collectionIcon={<PlayCircle />} toolbarActions={<><span className="run-record-count">{tr ? `${loadedRuns.length.toLocaleString(locale)} / ${totalRuns.toLocaleString(locale)} kayıt gösteriliyor` : `Showing ${loadedRuns.length.toLocaleString(locale)} of ${totalRuns.toLocaleString(locale)} records`}</span>{refreshControls}</>}>
        <thead><tr><th data-field-key="name">{t('object')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="startedAt">{tr ? 'Başlangıç Zamanı' : 'Start Time'}</th><th data-field-key="duration">{t('duration')}</th><th data-field-key="finishedAt">{tr ? 'Bitiş Zamanı' : 'End Time'}</th><th data-field-key="environment">{t('environment')}</th><th data-field-key="type">{t('objectType')}</th><th data-field-key="selected">{t('selectedRows')}</th><th data-field-key="inserted">{t('insertedRows')}</th><th data-field-key="updated">{tr ? 'Güncellenen' : 'Updated'}</th><th data-field-key="deleted">{tr ? 'Silinen' : 'Deleted'}</th><th data-field-key="trigger">{t('trigger')}</th><th data-field-key="initiator">{t('initiator')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{t('actions')}</span></th></tr></thead>
        <tbody>{loadedRuns.map((item) => <tr key={item.run.runUuid} data-connection-uuid={item.run.runUuid}>
          <td><span className="connection-record-identity"><strong>{item.definitionName}</strong><small>{item.definitionCode}</small></span></td>
          <td>{statusTag(item.run.status)}</td>
          <td>{formatOperationalDateTime(item.run.startedAt ?? item.run.createdAt)}</td>
          <td>{formatOperationalDuration(item.run.startedAt, item.run.finishedAt, locale, t('notRecorded'))}</td>
          <td>{formatOperationalDateTime(item.run.finishedAt, t('notRecorded'))}</td>
          <td><span className="connection-record-identity"><strong>{item.environmentName}</strong><small>{item.environmentCode} · {executionCodeLabel(item.environmentRisk, locale)}</small></span></td>
          <td><Tag className="run-object-type" data-object-type={item.definitionType}>{executionCodeLabel(item.definitionType, locale)}</Tag></td>
          <td className="run-numeric">{count(item.selectedRowsExact, item.selectedRows)}</td>
          <td className="run-numeric">{count(item.insertedRowsExact, item.insertedRows)}</td>
          <td className="run-numeric">{count(item.updatedRowsExact, item.updatedRows)}</td>
          <td className="run-numeric">{count(item.deletedRowsExact, item.deletedRows)}</td>
          <td><span className="connection-record-identity run-trigger-value"><span>{item.scheduleCode ? (tr ? 'Otomatik' : 'Automatic') : (tr ? 'Manuel' : 'Manual')}</span>{item.scheduleCode && <small>{item.scheduleCode}</small>}</span></td>
          <td>{item.initiatorName}</td>
          <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={item.definitionName} editable={false} onClick={() => openRun(item.run.runUuid)} /></div></td>
        </tr>)}</tbody>
      </DataGrid>}
      {runs.data && <div ref={scrollSentinel} className="run-scroll-sentinel" aria-live="polite">{loadingMore ? <><LoaderCircle className="spin" /> {tr ? 'Diğer kayıtlar yükleniyor…' : 'Loading more records…'}</> : hasMore ? (tr ? 'Aşağı kaydırdıkça diğer kayıtlar yüklenir.' : 'More records load as you scroll.') : (tr ? `Tüm ${totalRuns.toLocaleString(locale)} kayıt gösteriliyor.` : `All ${totalRuns.toLocaleString(locale)} records are shown.`)}</div>}
    </section>
    {selectedRunUuid ? <RunDetailPage runUuidOverride={selectedRunUuid} panel onClose={closeRun} objectName={loadedRuns.find((item) => item.run.runUuid === selectedRunUuid)?.definitionName} /> : null}
  </section>
}
