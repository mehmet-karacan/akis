import { Input, Radio } from 'antd'
import { CalendarClock, Clock3, Save, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Button } from '../../core/ui/Button'
import { RecordDetailDialog } from '../../core/ui/RecordDetailDialog'
import { Select } from '../../core/ui/Select'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useExecutionI18n } from './i18n'
import { scheduleApi } from './scheduleApi'
import type { CreateScheduleInput, Schedule } from './scheduleTypes'

type Frequency = 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'CUSTOM'
/** The schedule always runs in the browser's own time zone — the editor never asks for a raw IANA zone or a UTC hour;
 * "20:00" typed here means 20:00 where the person configuring it is sitting. */
const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone
const blank: CreateScheduleInput = { kod: '', ad: '', publicationUuid: '', cronExpression: '0 0 * * * *', timeZone: localZone, conflictPolicy: 'SKIP', misfirePolicy: 'SKIP' }
const frequencyOf = (cron: string): Frequency => /^0 \d+ \* \* \* \*$/.test(cron) ? 'HOURLY' : /^0 \d+ \d+ \* \* \*$/.test(cron) ? 'DAILY' : /^0 \d+ \d+ \* \* [1-7]$/.test(cron) ? 'WEEKLY' : /^0 \d+ \d+ \d+ \* \?$/.test(cron) ? 'MONTHLY' : 'CUSTOM'
const cronFor = (frequency: Frequency, hour: number, minute: number, weekday: number, monthday: number) => ({ HOURLY: `0 ${minute} * * * *`, DAILY: `0 ${minute} ${hour} * * *`, WEEKLY: `0 ${minute} ${hour} * * ${weekday}`, MONTHLY: `0 ${minute} ${hour} ${monthday} * ?`, CUSTOM: '' })[frequency]

/** Next N occurrences in the browser's own local time — accurate as long as the schedule's stored zone is that same
 * local zone (true for anything created or re-saved by this editor; a schedule saved under a different zone shows no
 * preview rather than a wrong one). */
function nextOccurrences(frequency: Frequency, hour: number, minute: number, weekday: number, monthday: number, timeZone: string, count: number): Date[] {
  if (timeZone !== localZone || frequency === 'CUSTOM') return []
  const results: Date[] = []
  const cursor = new Date()
  cursor.setSeconds(0, 0)
  cursor.setMinutes(cursor.getMinutes() + 1)
  let guard = 0
  while (results.length < count && guard < 100000) {
    guard += 1
    const matches = frequency === 'HOURLY' ? cursor.getMinutes() === minute
      : frequency === 'DAILY' ? cursor.getMinutes() === minute && cursor.getHours() === hour
      : frequency === 'WEEKLY' ? cursor.getMinutes() === minute && cursor.getHours() === hour && (cursor.getDay() === 0 ? 7 : cursor.getDay()) === weekday
      : cursor.getMinutes() === minute && cursor.getHours() === hour && cursor.getDate() === monthday
    if (matches) results.push(new Date(cursor))
    cursor.setMinutes(cursor.getMinutes() + 1)
  }
  return results
}

export function ScheduleEditorPanel({ open, schedule, projectUuid, publications, onClose, onSaved }: { open: boolean; schedule: Schedule | null; projectUuid: string; publications: { uuid: string; label: string; active: boolean; risk: string }[]; onClose(): void; onSaved(): Promise<void> }) {
  const { t, locale } = useExecutionI18n(); const tr = locale.startsWith('tr')
  const [draft, setDraft] = useState<CreateScheduleInput>(blank)
  const [frequency, setFrequency] = useState<Frequency>('HOURLY')
  const [hour, setHour] = useState(0); const [minute, setMinute] = useState(0); const [weekday, setWeekday] = useState(1); const [monthday, setMonthday] = useState(1)
  const [desiredStatus, setDesiredStatus] = useState<'AKTIF' | 'ASKIDA'>('ASKIDA')
  const [saving, setSaving] = useState(false); const [error, setError] = useState<unknown>(null)
  useEffect(() => { if (!open) return; const next = schedule ? { kod: schedule.kod, ad: schedule.ad, publicationUuid: schedule.publicationUuid, cronExpression: schedule.cronExpression, timeZone: localZone, conflictPolicy: schedule.conflictPolicy, misfirePolicy: schedule.misfirePolicy } : blank; setDraft(next); setFrequency(frequencyOf(next.cronExpression)); const parts = next.cronExpression.split(' '); setMinute(Number(parts[1]) || 0); setHour(Number(parts[2]) || 0); setMonthday(Number(parts[3]) || 1); setWeekday(Number(parts[5]) || 1); setDesiredStatus(schedule?.status ?? 'ASKIDA'); setError(null) }, [open, schedule])
  const update = <K extends keyof CreateScheduleInput>(key: K, value: CreateScheduleInput[K]) => setDraft(current => ({ ...current, [key]: value }))
  const occurrences = useMemo(() => nextOccurrences(frequency, hour, minute, weekday, monthday, draft.timeZone, 3), [frequency, hour, minute, weekday, monthday, draft.timeZone])
  const selectedPublication = publications.find(item => item.uuid === draft.publicationUuid)
  const save = async (event: FormEvent) => { event.preventDefault(); setSaving(true); setError(null); try { const input = { ...draft, timeZone: localZone, cronExpression: frequency === 'CUSTOM' ? draft.cronExpression.trim() : cronFor(frequency, hour, minute, weekday, monthday) }; const result = schedule ? await scheduleApi.update(projectUuid, schedule.uuid, schedule.version, input) : await scheduleApi.create(projectUuid, input); if (desiredStatus !== result.status) { if (desiredStatus === 'AKTIF') await scheduleApi.resume(projectUuid, result.uuid, result.version); else await scheduleApi.pause(projectUuid, result.uuid, result.version) } await onSaved(); onClose() } catch (reason) { setError(reason) } finally { setSaving(false) } }
  const pad = (value: number) => String(value).padStart(2, '0')
  const timeOfDay = `${pad(hour)}:${pad(minute)}`
  const setTimeOfDay = (value: string) => { const [h, m] = value.split(':'); const hourValue = Number(h); const minuteValue = Number(m); if (Number.isFinite(hourValue)) setHour(hourValue); if (Number.isFinite(minuteValue)) setMinute(minuteValue) }
  return <RecordDetailDialog open={open} onClose={onClose} busy={saving} className="schema-metadata-panel-modal schedule-editor-modal" title={<span className="ui-inline-title"><CalendarClock size={18} />{schedule ? (tr ? 'Zamanlamayı Düzenle' : 'Edit schedule') : t('createSchedule')}</span>}>
    <form className="schedule-editor-form" onSubmit={event => void save(event)}>
      {Boolean(error) && <div className="error-banner" role="alert">{apiErrorMessage(error, t('requestFailed'))}</div>}
      <fieldset disabled={saving}><legend>{tr ? 'Tanım' : 'Definition'}</legend><div className="schedule-editor-grid"><label><span>{t('scheduleName')} *</span><Input required value={draft.ad} onChange={e => update('ad', e.target.value)} /></label><label><span>{t('scheduleCode')} *</span><Input required pattern="[A-Z][A-Z0-9_]{0,99}" value={draft.kod} onChange={e => update('kod', e.target.value.toUpperCase())} /></label><label className="schedule-editor-wide"><span>{t('publication')} *</span><Select required value={draft.publicationUuid} onChange={e => update('publicationUuid', e.target.value)}><option value="">{t('choosePublication')}</option>{publications.filter(item => item.active || item.uuid === draft.publicationUuid).map(item => <option key={item.uuid} value={item.uuid}>{item.risk === 'URETIM' ? `⚠ ${item.label}` : item.label}</option>)}</Select>{selectedPublication?.risk === 'URETIM' && <small className="schedule-production-warning">{tr ? 'Bu yayın üretim ortamında; zamanlama üretim verisini etkiler.' : 'This publication targets production; the schedule will affect production data.'}</small>}</label></div></fieldset>
      <fieldset disabled={saving}><legend>{t('status')}</legend><Radio.Group value={desiredStatus} onChange={e => setDesiredStatus(e.target.value)} className="schedule-radio-group"><Radio value="AKTIF">{t('scheduleStatus_AKTIF')}</Radio><Radio value="ASKIDA">{t('scheduleStatus_ASKIDA')}</Radio></Radio.Group></fieldset>
      <fieldset disabled={saving}><legend>{tr ? 'Çalışma sıklığı' : 'Execution frequency'}</legend><Radio.Group value={frequency} onChange={e => setFrequency(e.target.value)} className="schedule-radio-group">{(['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'CUSTOM'] as const).map(value => <Radio key={value} value={value}>{({ HOURLY: tr ? 'Saatlik' : 'Hourly', DAILY: tr ? 'Günlük' : 'Daily', WEEKLY: tr ? 'Haftalık' : 'Weekly', MONTHLY: tr ? 'Aylık' : 'Monthly', CUSTOM: tr ? 'Özel cron' : 'Custom cron' })[value]}</Radio>)}</Radio.Group><div className="schedule-editor-grid">
          {frequency === 'HOURLY' && <label><span>{tr ? 'Saatin kaçıncı dakikası' : 'Minute of the hour'}</span><Input type="number" min={0} max={59} required value={minute} onChange={e => setMinute(Number(e.target.value))} /></label>}
          {['DAILY', 'WEEKLY', 'MONTHLY'].includes(frequency) && <label><span>{tr ? 'Saat' : 'Time'}</span><Input type="time" required value={timeOfDay} onChange={e => setTimeOfDay(e.target.value)} /></label>}
          {frequency === 'WEEKLY' && <label><span>{tr ? 'Gün' : 'Day'}</span><Select value={weekday} onChange={e => setWeekday(Number(e.target.value))}>{(tr ? ['Pazartesi','Salı','Çarşamba','Perşembe','Cuma','Cumartesi','Pazar'] : ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']).map((day, index) => <option key={day} value={index + 1}>{day}</option>)}</Select></label>}
          {frequency === 'MONTHLY' && <label><span>{tr ? 'Ayın günü' : 'Day of month'}</span><Input type="number" min={1} max={28} required value={monthday} onChange={e => setMonthday(Number(e.target.value))} /></label>}
          {frequency === 'CUSTOM' && <label className="schedule-editor-wide"><span>{t('scheduleCron')}</span><Input required value={draft.cronExpression} onChange={e => update('cronExpression', e.target.value)} /><small>{t('scheduleCronHelp')}</small></label>}
        </div>
        {frequency !== 'CUSTOM' && <p className="schedule-timezone-note"><Clock3 size={12} aria-hidden="true" /> {tr ? `Saatler kendi saat diliminize göre (${localZone}).` : `Times are in your own time zone (${localZone}).`}</p>}
        <div className="schedule-preview">
          <strong><Sparkles size={14} aria-hidden="true" /> {tr ? 'Sonraki 3 çalışma zamanı' : 'Next 3 run times'}</strong>
          {occurrences.length > 0
            ? <ul>{occurrences.map(date => <li key={date.toISOString()}>{formatDate(date.toISOString(), locale)}</li>)}</ul>
            : <span className="schedule-preview-empty">{frequency === 'CUSTOM' ? (tr ? 'Özel cron ifadeleri için önizleme kaydettikten sonra hesaplanır.' : 'Custom cron expressions are previewed after saving.') : (tr ? 'UTC dışındaki zaman dilimleri için önizleme kaydettikten sonra hesaplanır.' : 'Time zones other than UTC are previewed after saving.')}</span>}
        </div>
      </fieldset>
      <fieldset disabled={saving}><legend>{tr ? 'Çalışma kuralları' : 'Run rules'}</legend><div className="schedule-editor-grid"><label><span>{t('scheduleConflictPolicy')}</span><Select value={draft.conflictPolicy} onChange={e => update('conflictPolicy', e.target.value as CreateScheduleInput['conflictPolicy'])}><option value="SKIP">{t('conflict_SKIP')}</option><option value="QUEUE">{t('conflict_QUEUE')}</option></Select></label><label><span>{t('scheduleMisfirePolicy')}</span><Select value={draft.misfirePolicy} onChange={e => update('misfirePolicy', e.target.value as CreateScheduleInput['misfirePolicy'])}><option value="SKIP">{t('misfire_SKIP')}</option><option value="RUN_ONCE">{t('misfire_RUN_ONCE')}</option></Select></label></div></fieldset>
      <footer className="schedule-editor-actions"><Button type="button" onClick={onClose} disabled={saving}>{tr ? 'Vazgeç' : 'Cancel'}</Button><Button tone="primary" icon={<Save size={16} />} type="submit" busy={saving}>{schedule ? (tr ? 'Kaydet' : 'Save') : t('createSchedule')}</Button></footer>
    </form>
  </RecordDetailDialog>
}
