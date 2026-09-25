import { Tag } from 'antd'
import { CalendarClock, CheckCircle2, Clock3, Edit3, History, Pause, Play, Plus, ShieldAlert, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Button as AntActionButton } from '../../core/ui/Button'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { Dialog } from '../../core/ui/Dialog'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { definitionsApi } from '../definitions/api'
import { operationsApi } from '../operations/api'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { ScheduleEditorPanel } from './ScheduleEditorPanel'
import { scheduleApi } from './scheduleApi'
import type { Schedule } from './scheduleTypes'
import { executionCodeLabel, useExecutionI18n } from './i18n'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

/** connectionStatusTagStyles has no "info" tone; last-run history is informational, not success/warning/danger/neutral. */
const lastRunTagStyle = {
  color: 'var(--schema-color-info)',
  borderColor: 'color-mix(in srgb, var(--schema-color-info) 38%, var(--line))',
  background: 'color-mix(in srgb, var(--schema-color-info) 11%, var(--surface))',
}

export function SchedulesPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, locale } = useExecutionI18n()
  const tr = locale.startsWith('tr')
  const [view, setView] = useCollectionView('akis:schedules:view')
  const schedules = useRemoteData(() => scheduleApi.list(projectUuid), [projectUuid])
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const definitions = useRemoteData(() => definitionsApi.listDefinitions(projectUuid), [projectUuid])
  const [editorOpen, setEditorOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<Schedule | null>(null)
  const [formError, setFormError] = useState<unknown>(null)
  const [pendingUuid, setPendingUuid] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Schedule | null>(null)

  const publicationParts = (publicationUuid: string) => {
    const publication = publications.data?.find((item) => item.uuid === publicationUuid)
    if (!publication) return { name: publicationUuid.slice(0, 8), meta: '', risk: null as string | null }
    const definition = definitions.data?.find((item) => item.uuid === publication.definitionUuid)
    return { name: definition?.name ?? '—', meta: `#${publication.publicationNumber} · ${publication.environmentCode}`, risk: publication.environmentRisk }
  }
  const publicationLabel = (publicationUuid: string) => { const parts = publicationParts(publicationUuid); return parts.meta ? `${parts.name} · ${parts.meta}` : parts.name }
  const relativeTime = (value: string | null) => {
    if (!value) return null
    const diffMs = Date.parse(value) - Date.now()
    const past = diffMs < 0
    const minutes = Math.round(Math.abs(diffMs) / 60000)
    const text = minutes < 1 ? (tr ? 'şimdi' : 'now')
      : minutes < 60 ? (tr ? `${minutes} dk` : `${minutes}m`)
      : minutes < 1440 ? (tr ? `${Math.round(minutes / 60)} sa` : `${Math.round(minutes / 60)}h`)
      : (tr ? `${Math.round(minutes / 1440)} gün` : `${Math.round(minutes / 1440)}d`)
    if (minutes < 1) return tr ? 'şimdi' : 'now'
    return past ? (tr ? `${text} önce` : `${text} ago`) : (tr ? `${text} sonra` : `in ${text}`)
  }
  const [query, setQuery] = useState('')
  const togglePause = async (schedule: Schedule) => {
    setPendingUuid(schedule.uuid)
    try {
      if (schedule.status === 'AKTIF') await scheduleApi.pause(projectUuid, schedule.uuid, schedule.version)
      else await scheduleApi.resume(projectUuid, schedule.uuid, schedule.version)
      await schedules.reload()
    } catch (error) { setFormError(error) } finally { setPendingUuid(null) }
  }
  const confirmDelete = async () => {
    if (!deleteTarget) return
    setPendingUuid(deleteTarget.uuid)
    try {
      await scheduleApi.remove(projectUuid, deleteTarget.uuid, deleteTarget.version)
      setDeleteTarget(null)
      await schedules.reload()
    } catch (error) { setFormError(error) } finally { setPendingUuid(null) }
  }

  const allItems = schedules.data ?? []
  const items = useMemo(() => allItems.filter((schedule) => `${schedule.ad} ${schedule.kod} ${publicationLabel(schedule.publicationUuid)} ${schedule.cronExpression}`.toLocaleLowerCase(locale).includes(query.toLocaleLowerCase(locale))), [allItems, query, locale, publications.data, definitions.data])
  const nextRunOverall = useMemo(() => allItems
    .filter((item) => item.status === 'AKTIF' && item.nextFireTime)
    .map((item) => item.nextFireTime as string)
    .sort()[0] ?? null, [allItems])
  const createAction = <AntActionButton tone="primary" icon={<Plus size={16} />} className="ops-button" type="button" onClick={() => { setEditTarget(null); setEditorOpen(true) }}>{t('createSchedule')}</AntActionButton>

  const deleteDialog = <Dialog open={Boolean(deleteTarget)} title={t('confirmDeleteSchedule')} closeLabel={t('close')} onClose={() => setDeleteTarget(null)} busy={pendingUuid === deleteTarget?.uuid} className="akis-modal">
    <p>{t('confirmDeleteScheduleHelp')}</p>
    <div className="run-filter-actions">
      <AntActionButton tone="danger" className="ops-button" type="button" disabled={pendingUuid === deleteTarget?.uuid} onClick={() => void confirmDelete()}>{t('deleteSchedule')}</AntActionButton>
      <AntActionButton tone="secondary" className="ops-button ops-button-secondary" type="button" onClick={() => setDeleteTarget(null)}>{t('close')}</AntActionButton>
    </div>
  </Dialog>

  return <section className="page-stack connections-page execution-page">
    <section className="connection-management-panel"><PageHeader icon={<CalendarClock />} eyebrow={tr ? 'OPERASYON' : 'OPERATIONS'} title={t('schedules')} description={t('schedulesHelp')} />
    <QueryFilter onApply={setQuery} placeholder={tr ? 'Ad, kod, yayın veya cron ifadesinde ara' : 'Search name, code, publication or cron expression'} /></section>
    <SummaryStrip ariaLabel={t('schedules')} items={[
      { label: t('schedules'), value: items.length, icon: <CalendarClock />, tone: 'info' },
      { label: t('scheduleStatus_AKTIF'), value: items.filter((item) => item.status === 'AKTIF').length, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('scheduleStatus_ASKIDA'), value: items.filter((item) => item.status === 'ASKIDA').length, icon: <Pause />, tone: 'neutral' },
      { label: t('nextFireTime'), value: nextRunOverall ? formatDate(nextRunOverall, locale) : '—', hint: nextRunOverall ? (relativeTime(nextRunOverall) ?? undefined) : undefined, icon: <Clock3 />, tone: 'warning' },
    ]} />
    <section className="connections-records">
      {Boolean(formError) && <div className="error-banner" role="alert">{apiErrorMessage(formError, t('requestFailed'))}</div>}
      {schedules.loading ? <AsyncState state="loading" title={t('loading')} /> : schedules.error ? <AsyncState state="error" title={apiErrorMessage(schedules.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void schedules.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={t('emptySchedules')} action={createAction} /> : <ProgressiveRecords items={items}>{(visible) => <DataGrid collectionTitle={t('schedules')} collectionIcon={<CalendarClock />} toolbarActions={createAction} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="name">{t('scheduleName')}</th><th data-field-key="publication">{t('publication')}</th><th data-field-key="cron">{t('scheduleCron')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="next">{t('nextFireTime')}</th><th data-field-key="last">{t('lastFireTime')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{t('actions')}</span></th></tr></thead>
        <tbody>{visible.map((schedule) => { const publication = publicationParts(schedule.publicationUuid); const relative = schedule.status === 'AKTIF' ? relativeTime(schedule.nextFireTime) : null; return <tr key={schedule.uuid} data-connection-uuid={schedule.uuid}>
          <td><span className="connection-record-identity">
            <strong>{schedule.ad}</strong>
            <small>{schedule.kod}</small>
            {relative && <span className="schedule-next-run-chip"><Clock3 size={11} aria-hidden="true" />{relative}</span>}
          </span></td>
          <td><span className="connection-record-identity schedule-publication">
            <strong>{publication.name}</strong>
            <small>{publication.meta}{publication.risk === 'URETIM' && <span className="schedule-risk-badge"><ShieldAlert size={11} aria-hidden="true" />{executionCodeLabel('URETIM', locale)}</span>}</small>
          </span></td>
          <td><code>{schedule.cronExpression}</code><small className="schedule-timezone">{schedule.timeZone}</small></td>
          <td>{(() => { const tone = schedule.status === 'AKTIF' ? 'success' : 'neutral'; return <Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={schedule.status === 'AKTIF' ? <CheckCircle2 size={12} /> : <Pause size={12} />}><span className="connection-status-tag-label">{t(`scheduleStatus_${schedule.status}` as Parameters<typeof t>[0])}</span></Tag> })()}</td>
          <td>{schedule.nextFireTime ? <Tag className="connection-status-tag connection-status-tag--warning" style={connectionStatusTagStyles.warning} icon={<Clock3 size={12} />}><span className="connection-status-tag-label">{formatDate(schedule.nextFireTime, locale)}</span></Tag> : <span className="schedule-muted-cell">{t('notRecorded')}</span>}</td>
          <td>{schedule.lastFireTime ? <Tag className="connection-status-tag connection-status-tag--info" style={lastRunTagStyle} icon={<History size={12} />}><span className="connection-status-tag-label">{formatDate(schedule.lastFireTime, locale)}</span></Tag> : <span className="schedule-muted-cell">{t('notRecorded')}</span>}</td>
          <td className="row-actions"><div className="connection-row-actions">
            <AntActionButton tone="ghost" type="button" aria-label={tr ? 'Düzenle' : 'Edit'} title={tr ? 'Düzenle' : 'Edit'} icon={<Edit3 size={14} />} onClick={() => { setEditTarget(schedule); setEditorOpen(true) }} />
            <AntActionButton tone="ghost" type="button" aria-label={schedule.status === 'AKTIF' ? t('pauseSchedule') : t('resumeSchedule')} title={schedule.status === 'AKTIF' ? t('pauseSchedule') : t('resumeSchedule')} icon={schedule.status === 'AKTIF' ? <Pause size={14} /> : <Play size={14} />} disabled={pendingUuid === schedule.uuid} onClick={() => void togglePause(schedule)} />
            <AntActionButton tone="ghost" type="button" aria-label={t('deleteSchedule')} title={t('deleteSchedule')} icon={<Trash2 size={14} />} disabled={pendingUuid === schedule.uuid} onClick={() => setDeleteTarget(schedule)} />
          </div></td>
        </tr> })}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>
    <ScheduleEditorPanel open={editorOpen} schedule={editTarget} projectUuid={projectUuid} publications={(publications.data ?? []).map(item => ({ uuid: item.uuid, label: publicationLabel(item.uuid), active: item.status === 'AKTIF', risk: item.environmentRisk }))} onClose={() => setEditorOpen(false)} onSaved={schedules.reload} />{deleteDialog}
  </section>
}
