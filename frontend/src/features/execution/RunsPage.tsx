import { Button as AntActionButton } from '../../core/ui/Button'
import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput, Tag } from 'antd'
import { Activity, CheckCircle2, ChevronLeft, ChevronRight, Clock3, PlayCircle, RefreshCw, Repeat2, Search, Timer, XCircle } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from '../operations/api'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import { exactCount } from './types'
import { AsyncState, PageHeader, RecordActionButton } from '../../core/ui'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { useCollectionView } from '../../core/ui/ViewToggle'
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

const views: RunView[] = ['RECENT', 'ACTIVE', 'FAILED', 'HISTORY']
const activeStatuses = new Set(['BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT'])
const allowedSizes = [25, 50, 100]

function validView(value: string | null): RunView { return views.includes(value as RunView) ? value as RunView : 'RECENT' }
function positiveInt(value: string | null, fallback: number) { const parsed = Number(value); return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback }
function duration(startedAt: string | null, finishedAt: string | null, locale: string) {
  if (!startedAt) return locale.startsWith('tr') ? 'Başlamadı' : 'Not started'
  const milliseconds = (finishedAt ? Date.parse(finishedAt) : Date.now()) - Date.parse(startedAt)
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return locale.startsWith('tr') ? 'Hesaplanamadı' : 'Unavailable'
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
  const [collectionView, setCollectionView] = useCollectionView('akis:runs:view')
  const triggerParam = searchParams.get('trigger') ?? ''
  const [filterDraft, setFilterDraft] = useState({ view, status, environment, definitionType, trigger: triggerParam })
  const [queryDraft, setQueryDraft] = useState(query); const [fromDraft, setFromDraft] = useState(from); const [toDraft, setToDraft] = useState(to); const [live, setLive] = useState(false)
  const [refreshSeconds, setRefreshSeconds] = useState('15')
  const refreshInterval = Number(refreshSeconds)
  const validInterval = Number.isInteger(refreshInterval) && refreshInterval >= 1 && refreshInterval <= 86400
  const refreshing = useRef(false)
  const tr = locale.startsWith('tr')
  const scheduled = triggerParam === 'SCHEDULED' ? true : triggerParam === 'MANUAL' ? false : undefined
  const searchInput: RunSearchInput = { view, query, statuses: status, environment, definitionType, from, to, page, size, scheduled }
  const runs = useRemoteData(() => executionApi.searchRuns(projectUuid, searchInput), [projectUuid, view, query, status, environment, definitionType, from, to, page, size, scheduled])
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

  const statusTag = (runStatus: string) => { const presentation = runStatusPresentation(runStatus, t); return <Tag className={`connection-status-tag connection-status-tag--${presentation.tone} run-status run-status--${runStatus.toLowerCase()}`} style={connectionStatusTagStyles[presentation.tone]} icon={presentation.icon}><span className="connection-status-tag-label">{presentation.label}</span></Tag> }
  const applyFilters = () => updateFilters({ view: filterDraft.view === 'RECENT' ? '' : filterDraft.view, query: queryDraft, statuses: filterDraft.status, environment: filterDraft.environment, definitionType: filterDraft.definitionType, trigger: filterDraft.trigger })
  const clearFilters = () => { setFilterDraft({ view: 'RECENT', status: '', environment: '', definitionType: '', trigger: '' }); setQueryDraft(''); setFromDraft(''); setToDraft(''); updateFilters({ view: '', query: '', statuses: '', environment: '', definitionType: '', from: '', to: '', trigger: '' }) }
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
      <label><span>{tr ? 'Görünüm' : 'View'}</span><FormSelect aria-label={tr ? 'Görünüm' : 'View'} value={filterDraft.view} onChange={(event) => setFilterDraft(current => ({ ...current, view: validView(event.target.value) }))}>{views.map((item) => <option key={item} value={item}>{t(`view_${item}` as Parameters<typeof t>[0])}</option>)}</FormSelect></label>
      <label className="execution-search"><Search aria-hidden="true" /><span>{t('searchRuns')}</span><AntInput value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} placeholder={t('searchRunsPlaceholder')} /></label>
      <label><span>{t('status')}</span><FormSelect aria-label={t('status')} value={filterDraft.status} onChange={(event) => setFilterDraft(current => ({ ...current, status: event.target.value }))}><option value="">{t('allStatuses')}</option><option value="CALISIYOR">{t('status_CALISIYOR')}</option><option value="BASARISIZ">{t('status_BASARISIZ')}</option><option value="BASARILI">{t('status_BASARILI')}</option><option value="SONUCU_BILINMIYOR">{t('status_SONUC_BELIRSIZ')}</option></FormSelect></label>
      <label><span>{t('environment')}</span><FormSelect aria-label={t('environment')} value={filterDraft.environment} onChange={(event) => setFilterDraft(current => ({ ...current, environment: event.target.value }))}><option value="">{t('allEnvironments')}</option>{environmentCodes.map((code) => <option key={code}>{code}</option>)}</FormSelect></label>
      <label><span>{t('objectType')}</span><FormSelect aria-label={t('objectType')} value={filterDraft.definitionType} onChange={(event) => setFilterDraft(current => ({ ...current, definitionType: event.target.value }))}><option value="">{t('allObjectTypes')}</option><option value="PROSEDUR">{t('procedure')}</option><option value="MAPPING">{t('mapping')}</option><option value="PAKET">{t('package')}</option></FormSelect></label>
      <label><span>{t('trigger')}</span><FormSelect aria-label={t('trigger')} value={filterDraft.trigger} onChange={(event) => setFilterDraft(current => ({ ...current, trigger: event.target.value }))}><option value="">{t('allTriggers')}</option><option value="SCHEDULED">{executionCodeLabel('SCHEDULED', locale)}</option><option value="MANUAL">{executionCodeLabel('MANUAL', locale)}</option></FormSelect></label>
      {filterDraft.view === 'HISTORY' && <><label><span>{t('from')}</span><AntInput type="datetime-local" value={fromDraft ? fromDraft.slice(0, 16) : ''} onChange={(event) => setFromDraft(event.target.value)} /></label><label><span>{t('to')}</span><AntInput type="datetime-local" value={toDraft ? toDraft.slice(0, 16) : ''} onChange={(event) => setToDraft(event.target.value)} /></label></>}
      <div className="run-filter-actions"><AntActionButton tone="primary" icon={<Search size={16} />} className="ops-button" type="submit">{t('applyFilters')}</AntActionButton>
      <AntActionButton tone="secondary" icon={<XCircle size={16} />} className="ops-button ops-button-secondary" type="button" onClick={clearFilters}>{t('clearFilters')}</AntActionButton></div>
    </form><p className="form-note">{t(`scope_${view}` as Parameters<typeof t>[0])}</p></FilterSection></section>
    <SummaryStrip ariaLabel={t('runs')} items={[
      { label: tr ? 'Toplam Çalıştırma' : 'Total Runs', value: runs.data?.total ?? 0, icon: <Activity />, tone: 'info' },
      { label: t('status_CALISIYOR'), value: runs.data?.items.filter((item) => activeStatuses.has(item.run.status)).length ?? 0, icon: <Clock3 />, tone: 'warning' },
      { label: t('status_BASARILI'), value: runs.data?.items.filter((item) => item.run.status === 'BASARILI').length ?? 0, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('status_BASARISIZ'), value: runs.data?.items.filter((item) => item.run.status === 'BASARISIZ').length ?? 0, icon: <XCircle />, tone: 'danger' },
    ]} />
    <section className="connections-records">
      {Boolean(runs.error) && runs.data && <div className="error-banner" role="alert">{t('refreshFailed')} <AntActionButton tone="ghost" type="button" onClick={() => void runs.reload()}>{t('retry')}</AntActionButton></div>}
      {runs.loading && !runs.data ? <AsyncState state="loading" title={t('loading')} /> : Boolean(runs.error) && !runs.data ? <AsyncState state="error" title={apiErrorMessage(runs.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void runs.reload()} /> : !runs.data || runs.data.items.length === 0 ? <AsyncState state="empty" title={t('emptyRuns')} action={refreshControls} /> : <ProgressiveRecords key={`${view}:${query}:${page}`} items={runs.data.items}>{(visible) => <DataGrid collectionTitle={tr ? 'Çalıştırma Listesi' : 'Run List'} collectionIcon={<PlayCircle />} toolbarActions={refreshControls} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={collectionView} onViewChange={setCollectionView}>
        <thead><tr><th data-field-key="name">{t('object')}</th><th data-field-key="environment">{t('environment')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="startedAt">{t('startedAt')}</th><th data-field-key="duration">{t('duration')}</th><th data-field-key="selected">{t('selectedRows')}</th><th data-field-key="inserted">{t('insertedRows')}</th><th data-field-key="initiator">{t('initiator')}</th><th data-field-key="trigger">{t('trigger')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{t('actions')}</span></th></tr></thead>
        <tbody>{visible.map((item) => <tr key={item.run.runUuid} data-connection-uuid={item.run.runUuid}>
          <td><span className="connection-record-identity"><strong>{item.definitionName}</strong><small>{executionCodeLabel(item.definitionType, locale)} · {item.definitionCode}</small></span></td>
          <td><span className="connection-record-identity"><strong>{item.environmentName}</strong><small>{item.environmentCode} · {executionCodeLabel(item.environmentRisk, locale)}</small></span></td>
          <td>{statusTag(item.run.status)}</td>
          <td>{formatDate(item.run.startedAt ?? item.run.createdAt, locale)}</td>
          <td>{duration(item.run.startedAt, item.run.finishedAt, locale)}</td>
          <td className="run-numeric">{count(item.selectedRowsExact, item.selectedRows)}</td>
          <td className="run-numeric">{count(item.insertedRowsExact, item.insertedRows)}</td>
          <td>{item.initiatorName}</td>
          <td>{item.scheduleCode ? <Tag className="connection-status-tag connection-status-tag--neutral" style={connectionStatusTagStyles.neutral}><span className="connection-status-tag-label">{item.scheduleCode}</span></Tag> : executionCodeLabel('MANUAL', locale)}</td>
          <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={item.definitionName} editable={false} onClick={() => openRun(item.run.runUuid)} /></div></td>
        </tr>)}</tbody>
      </DataGrid>}</ProgressiveRecords>}
      {runs.data && totalPages > 1 && <footer className="run-pagination"><label>{t('pageSize')}<FormSelect value={size} onChange={(event) => updateFilters({ size: event.target.value, page: '0' })}>{allowedSizes.map((value) => <option key={value}>{value}</option>)}</FormSelect></label><AntActionButton tone="ghost" type="button" disabled={page === 0} onClick={() => updateFilters({ page: String(page - 1) })} aria-label={t('previousPage')}><ChevronLeft /></AntActionButton><span>{page + 1} / {totalPages}</span><AntActionButton tone="ghost" type="button" disabled={page + 1 >= totalPages} onClick={() => updateFilters({ page: String(page + 1) })} aria-label={t('nextPage')}><ChevronRight /></AntActionButton></footer>}
    </section>
    {selectedRunUuid ? <RunDetailPage runUuidOverride={selectedRunUuid} panel onClose={closeRun} objectName={runs.data?.items.find((item) => item.run.runUuid === selectedRunUuid)?.definitionName} /> : null}
  </section>
}
