import { Input as AntInput, Tag } from 'antd'
import { CalendarClock, CheckCircle2, Pause, Play, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Select as FormSelect } from '../../core/ui/Select'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { Dialog } from '../../core/ui/Dialog'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { definitionsApi } from '../definitions/api'
import { operationsApi } from '../operations/api'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { scheduleApi } from './scheduleApi'
import type { CreateScheduleInput, Schedule, ScheduleConflictPolicy, ScheduleMisfirePolicy } from './scheduleTypes'
import { useExecutionI18n } from './i18n'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

const emptyDraft: CreateScheduleInput = {
  kod: '', ad: '', publicationUuid: '', cronExpression: '0 0 * * * *', timeZone: 'UTC',
  conflictPolicy: 'SKIP', misfirePolicy: 'SKIP',
}

export function SchedulesPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, locale } = useExecutionI18n()
  const tr = locale.startsWith('tr')
  const [view, setView] = useCollectionView('akis:schedules:view')
  const schedules = useRemoteData(() => scheduleApi.list(projectUuid), [projectUuid])
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const definitions = useRemoteData(() => definitionsApi.listDefinitions(projectUuid), [projectUuid])
  const [createOpen, setCreateOpen] = useState(false)
  const [draft, setDraft] = useState<CreateScheduleInput>(emptyDraft)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)
  const [pendingUuid, setPendingUuid] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Schedule | null>(null)

  const publicationLabel = (publicationUuid: string) => {
    const publication = publications.data?.find((item) => item.uuid === publicationUuid)
    if (!publication) return publicationUuid.slice(0, 8)
    const definition = definitions.data?.find((item) => item.uuid === publication.definitionUuid)
    return `${definition?.name ?? '—'} · #${publication.publicationNumber} · ${publication.environmentCode}`
  }
  const activeOrRunnable = (publications.data ?? []).filter((item) => item.status === 'AKTIF')

  const submitCreate = async (event: React.FormEvent) => {
    event.preventDefault()
    setSaving(true); setFormError(null)
    try {
      await scheduleApi.create(projectUuid, draft)
      setCreateOpen(false); setDraft(emptyDraft)
      await schedules.reload()
    } catch (error) { setFormError(error) } finally { setSaving(false) }
  }
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

  const items = schedules.data ?? []
  const createForm = <Dialog open={createOpen} title={t('createSchedule')} closeLabel={t('close')} onClose={() => setCreateOpen(false)} busy={saving} className="akis-modal">
    <form className="run-filters" onSubmit={(event) => { void submitCreate(event) }}>
      {Boolean(formError) && <div className="error-banner" role="alert">{apiErrorMessage(formError, t('requestFailed'))}</div>}
      <label><span>{t('scheduleCode')}</span><AntInput value={draft.kod} onChange={(event) => setDraft((current) => ({ ...current, kod: event.target.value.toUpperCase() }))} required pattern="[A-Z][A-Z0-9_]{0,99}" /></label>
      <label><span>{t('scheduleName')}</span><AntInput value={draft.ad} onChange={(event) => setDraft((current) => ({ ...current, ad: event.target.value }))} required /></label>
      <label><span>{t('publication')}</span><FormSelect value={draft.publicationUuid} onChange={(event) => setDraft((current) => ({ ...current, publicationUuid: event.target.value }))} required>
        <option value="" disabled>{t('choosePublication')}</option>
        {activeOrRunnable.map((publication) => <option key={publication.uuid} value={publication.uuid}>{publicationLabel(publication.uuid)}</option>)}
      </FormSelect></label>
      <label><span>{t('scheduleCron')}</span><AntInput value={draft.cronExpression} onChange={(event) => setDraft((current) => ({ ...current, cronExpression: event.target.value }))} required /><small className="form-note">{t('scheduleCronHelp')}</small></label>
      <label><span>{t('scheduleTimeZone')}</span><AntInput value={draft.timeZone} onChange={(event) => setDraft((current) => ({ ...current, timeZone: event.target.value }))} required /></label>
      <label><span>{t('scheduleConflictPolicy')}</span><FormSelect value={draft.conflictPolicy} onChange={(event) => setDraft((current) => ({ ...current, conflictPolicy: event.target.value as ScheduleConflictPolicy }))}>
        <option value="SKIP">{t('conflict_SKIP')}</option><option value="QUEUE">{t('conflict_QUEUE')}</option>
      </FormSelect></label>
      <label><span>{t('scheduleMisfirePolicy')}</span><FormSelect value={draft.misfirePolicy} onChange={(event) => setDraft((current) => ({ ...current, misfirePolicy: event.target.value as ScheduleMisfirePolicy }))}>
        <option value="SKIP">{t('misfire_SKIP')}</option><option value="RUN_ONCE">{t('misfire_RUN_ONCE')}</option>
      </FormSelect></label>
      <div className="run-filter-actions"><AntActionButton tone="primary" className="ops-button" type="submit" disabled={saving}>{saving ? t('creatingSchedule') : t('createSchedule')}</AntActionButton></div>
    </form>
  </Dialog>

  const deleteDialog = <Dialog open={Boolean(deleteTarget)} title={t('confirmDeleteSchedule')} closeLabel={t('close')} onClose={() => setDeleteTarget(null)} busy={pendingUuid === deleteTarget?.uuid} className="akis-modal">
    <p>{t('confirmDeleteScheduleHelp')}</p>
    <div className="run-filter-actions">
      <AntActionButton tone="danger" className="ops-button" type="button" disabled={pendingUuid === deleteTarget?.uuid} onClick={() => void confirmDelete()}>{t('deleteSchedule')}</AntActionButton>
      <AntActionButton tone="secondary" className="ops-button ops-button-secondary" type="button" onClick={() => setDeleteTarget(null)}>{t('close')}</AntActionButton>
    </div>
  </Dialog>

  return <section className="page-stack connections-page execution-page">
    <section className="connection-management-panel"><PageHeader icon={<CalendarClock />} eyebrow={tr ? 'OPERASYON' : 'OPERATIONS'} title={t('schedules')} description={t('schedulesHelp')}
      actions={<AntActionButton tone="primary" icon={<Plus size={16} />} className="ops-button" type="button" onClick={() => setCreateOpen(true)}>{t('createSchedule')}</AntActionButton>} /></section>
    <SummaryStrip ariaLabel={t('schedules')} items={[
      { label: t('schedules'), value: items.length, icon: <CalendarClock />, tone: 'info' },
      { label: t('scheduleStatus_AKTIF'), value: items.filter((item) => item.status === 'AKTIF').length, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('scheduleStatus_ASKIDA'), value: items.filter((item) => item.status === 'ASKIDA').length, icon: <Pause />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
      {schedules.loading ? <AsyncState state="loading" title={t('loading')} /> : schedules.error ? <AsyncState state="error" title={apiErrorMessage(schedules.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void schedules.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={t('emptySchedules')} /> : <ProgressiveRecords items={items}>{(visible) => <DataGrid collectionTitle={t('schedules')} collectionIcon={<CalendarClock />} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="name">{t('scheduleName')}</th><th data-field-key="publication">{t('publication')}</th><th data-field-key="cron">{t('scheduleCron')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="next">{t('nextFireTime')}</th><th data-field-key="last">{t('lastFireTime')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{t('actions')}</span></th></tr></thead>
        <tbody>{visible.map((schedule) => <tr key={schedule.uuid} data-connection-uuid={schedule.uuid}>
          <td><span className="connection-record-identity"><strong>{schedule.ad}</strong><small>{schedule.kod}</small></span></td>
          <td>{publicationLabel(schedule.publicationUuid)}</td>
          <td><code>{schedule.cronExpression}</code> <small>{schedule.timeZone}</small></td>
          <td><Tag className={`connection-status-tag connection-status-tag--${schedule.status === 'AKTIF' ? 'success' : 'neutral'}`} style={connectionStatusTagStyles[schedule.status === 'AKTIF' ? 'success' : 'neutral']}><span className="connection-status-tag-label">{t(`scheduleStatus_${schedule.status}` as Parameters<typeof t>[0])}</span></Tag></td>
          <td>{formatDate(schedule.nextFireTime, locale, '—')}</td>
          <td>{formatDate(schedule.lastFireTime, locale, '—')}</td>
          <td className="row-actions"><div className="connection-row-actions">
            <AntActionButton tone="ghost" type="button" aria-label={schedule.status === 'AKTIF' ? t('pauseSchedule') : t('resumeSchedule')} title={schedule.status === 'AKTIF' ? t('pauseSchedule') : t('resumeSchedule')} icon={schedule.status === 'AKTIF' ? <Pause size={14} /> : <Play size={14} />} disabled={pendingUuid === schedule.uuid} onClick={() => void togglePause(schedule)} />
            <AntActionButton tone="ghost" type="button" aria-label={t('deleteSchedule')} title={t('deleteSchedule')} icon={<Trash2 size={14} />} disabled={pendingUuid === schedule.uuid} onClick={() => setDeleteTarget(schedule)} />
          </div></td>
        </tr>)}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>
    {createForm}{deleteDialog}
  </section>
}
