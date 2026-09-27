import { Input, Radio } from 'antd'
import { CalendarClock, Save, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Button } from '../../core/ui/Button'
import { RecordDetailDialog } from '../../core/ui/RecordDetailDialog'
import { Select } from '../../core/ui/Select'
import { apiErrorMessage } from '../operations/utils'
import { useExecutionI18n } from './i18n'
import { scheduleApi } from './scheduleApi'
import { cronFor, describeSchedule, frequencyOf, hoursOf, intervalHoursOf, minuteIntervalOf, weekdaysOf, type Frequency } from './schedulePresets'
import type { CreateScheduleInput, Schedule, SchedulePublicationPolicy } from './scheduleTypes'
import { formatScheduleDate } from './scheduleFormatters'
import { fromZonedWallTime, toZonedWallTime } from './scheduleWindowTime'

const defaultZone = 'Europe/Istanbul'
const selectableZones = [defaultZone, 'UTC', 'Europe/Berlin', 'Europe/London', 'America/New_York', 'Asia/Dubai']
const blank: CreateScheduleInput = { kod: '', ad: '', publicationUuid: '', cronExpression: '0 0 * * * *', timeZone: defaultZone, conflictPolicy: 'SKIP', misfirePolicy: 'SKIP', publicationPolicy: 'LATEST_ACTIVE', desiredStatus: 'ASKIDA' }

export function ScheduleEditorPanel({ open, schedule, projectUuid, publications, onClose, onSaved }: { open: boolean; schedule: Schedule | null; projectUuid: string; publications: { uuid: string; label: string; active: boolean; risk: string }[]; onClose(): void; onSaved(): Promise<void> }) {
  const { t, locale } = useExecutionI18n(); const tr = locale.startsWith('tr')
  const [draft, setDraft] = useState<CreateScheduleInput>(blank)
  const [frequency, setFrequency] = useState<Frequency>('HOURLY')
  const [hour, setHour] = useState(0); const [minute, setMinute] = useState(0); const [weekdays, setWeekdays] = useState<number[]>([1]); const [multipleHours, setMultipleHours] = useState<number[]>([9, 17]); const [monthday, setMonthday] = useState(1); const [intervalHours, setIntervalHours] = useState(2); const [minuteInterval, setMinuteInterval] = useState(15)
  const [desiredStatus, setDesiredStatus] = useState<'AKTIF' | 'ASKIDA'>('ASKIDA')
  const [saving, setSaving] = useState(false); const [error, setError] = useState<unknown>(null)
  const [previewOccurrences, setPreviewOccurrences] = useState<string[]>([])
  const [previewErrors, setPreviewErrors] = useState<string[]>([])
  const [previewExhausted, setPreviewExhausted] = useState(false)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [windowStart, setWindowStart] = useState(''); const [windowEnd, setWindowEnd] = useState('')
  useEffect(() => { if (!open) return; const next = schedule ? { kod: schedule.kod, ad: schedule.ad, publicationUuid: schedule.publicationUuid, cronExpression: schedule.cronExpression, timeZone: schedule.timeZone, conflictPolicy: schedule.conflictPolicy, misfirePolicy: schedule.misfirePolicy, publicationPolicy: schedule.publicationPolicy, desiredStatus: schedule.status, startsAt: schedule.startsAt, endsAt: schedule.endsAt } : blank; const nextFrequency = frequencyOf(next.cronExpression); setDraft(next); setFrequency(nextFrequency); setWindowStart(schedule?.startsAt ? toZonedWallTime(schedule.startsAt, next.timeZone) : ''); setWindowEnd(schedule?.endsAt ? toZonedWallTime(schedule.endsAt, next.timeZone) : ''); const parts = next.cronExpression.split(' '); setMinute(Number(parts[1]) || 0); setHour(Number(parts[2]) || 0); setMonthday(Number(parts[3]) || 1); setWeekdays(weekdaysOf(next.cronExpression)); setMultipleHours(hoursOf(next.cronExpression)); setIntervalHours(intervalHoursOf(next.cronExpression)); setMinuteInterval(minuteIntervalOf(next.cronExpression)); setDesiredStatus(next.desiredStatus); setError(null); setPreviewOccurrences([]); setPreviewErrors([]); setPreviewExhausted(false) }, [open, schedule])
  const update = <K extends keyof CreateScheduleInput>(key: K, value: CreateScheduleInput[K]) => setDraft(current => ({ ...current, [key]: value }))
  const effectiveCron = useMemo(() => frequency === 'CUSTOM' ? draft.cronExpression.trim() : cronFor(frequency, hour, minute, weekdays, monthday, intervalHours, minuteInterval, multipleHours), [frequency, hour, minute, weekdays, monthday, intervalHours, minuteInterval, multipleHours, draft.cronExpression])
  const windowBounds = useMemo(() => {
    try { return { startsAt: windowStart ? fromZonedWallTime(windowStart, draft.timeZone || defaultZone) : null, endsAt: windowEnd ? fromZonedWallTime(windowEnd, draft.timeZone || defaultZone) : null, error: null as string | null } }
    catch (reason) { return { startsAt: null, endsAt: null, error: reason instanceof Error ? reason.message : String(reason) } }
  }, [windowStart, windowEnd, draft.timeZone])
  const selectedPublication = publications.find(item => item.uuid === draft.publicationUuid)
  useEffect(() => {
    let cancelled = false
    if (!open || windowBounds.error || (frequency === 'CUSTOM' && (!draft.cronExpression.trim() || draft.cronExpression.trim().split(' ').length < 6))) { setPreviewLoading(false); setPreviewOccurrences([]); setPreviewErrors(windowBounds.error ? [windowBounds.error] : []); setPreviewExhausted(false); return }
    setPreviewLoading(true)
    const timer = window.setTimeout(() => { void scheduleApi.preview(projectUuid, effectiveCron, draft.timeZone || defaultZone, windowBounds.startsAt, windowBounds.endsAt)
      .then(result => { if (!cancelled) { setPreviewOccurrences(result.nextOccurrences); setPreviewErrors(result.errors); setPreviewExhausted(result.exhausted) } })
      .catch(reason => { if (!cancelled) { setPreviewOccurrences([]); setPreviewErrors([apiErrorMessage(reason, tr ? 'Önizleme hesaplanamadı.' : 'Preview could not be calculated.')]); setPreviewExhausted(false) } })
      .finally(() => { if (!cancelled) setPreviewLoading(false) }) }, 250)
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [effectiveCron, draft.timeZone, projectUuid, frequency, open, tr, windowBounds])
  const save = async (event: FormEvent) => { event.preventDefault(); setSaving(true); setError(null); try { if (windowBounds.error) throw new Error(windowBounds.error); const input = { ...draft, desiredStatus, timeZone: draft.timeZone || defaultZone, cronExpression: effectiveCron, startsAt: windowBounds.startsAt, endsAt: windowBounds.endsAt }; if (schedule) await scheduleApi.update(projectUuid, schedule.uuid, schedule.version, input); else await scheduleApi.create(projectUuid, input); await onSaved(); onClose() } catch (reason) { setError(reason) } finally { setSaving(false) } }
  const pad = (value: number) => String(value).padStart(2, '0')
  const timeOfDay = `${pad(hour)}:${pad(minute)}`
  const setTimeOfDay = (value: string) => { const [h, m] = value.split(':'); const hourValue = Number(h); const minuteValue = Number(m); if (Number.isFinite(hourValue)) setHour(hourValue); if (Number.isFinite(minuteValue)) setMinute(minuteValue) }
  return <RecordDetailDialog open={open} onClose={onClose} busy={saving} className="schema-metadata-panel-modal schedule-editor-modal" title={<span className="ui-inline-title"><CalendarClock size={18} />{schedule ? (tr ? 'Zamanlamayı Düzenle' : 'Edit schedule') : t('createSchedule')}</span>}>
    <form className="schedule-editor-form" onSubmit={event => void save(event)}>
      {Boolean(error) && <div className="error-banner" role="alert">{apiErrorMessage(error, t('requestFailed'))}</div>}
      <fieldset disabled={saving}><legend>{tr ? 'Tanım' : 'Definition'}</legend><div className="schedule-editor-grid"><label><span>{t('scheduleName')} *</span><Input required value={draft.ad} onChange={e => update('ad', e.target.value)} /></label><label><span>{t('scheduleCode')} *</span><Input required pattern="[A-Z][A-Z0-9_]{0,99}" value={draft.kod} onChange={e => update('kod', e.target.value.toUpperCase())} /></label><label className="schedule-editor-wide"><span>{t('publication')} *</span><Select required value={draft.publicationUuid} onChange={e => update('publicationUuid', e.target.value)}><option value="">{t('choosePublication')}</option>{publications.filter(item => item.active || item.uuid === draft.publicationUuid).map(item => <option key={item.uuid} value={item.uuid}>{item.risk === 'URETIM' ? `⚠ ${item.label}` : item.label}</option>)}</Select>{selectedPublication?.risk === 'URETIM' && <small className="schedule-production-warning">{tr ? 'Bu yayın üretim ortamında; zamanlama üretim verisini etkiler.' : 'This publication targets production; the schedule will affect production data.'}</small>}</label></div></fieldset>
      <fieldset disabled={saving}><legend>{t('status')}</legend><Radio.Group value={desiredStatus} onChange={e => setDesiredStatus(e.target.value)} className="schedule-radio-group"><Radio value="AKTIF">{t('scheduleStatus_AKTIF')}</Radio><Radio value="ASKIDA">{t('scheduleStatus_ASKIDA')}</Radio></Radio.Group></fieldset>
      <fieldset disabled={saving}><legend>{tr ? 'Çalışma sıklığı' : 'Execution frequency'}</legend><Radio.Group value={frequency} onChange={e => setFrequency(e.target.value)} className="schedule-radio-group">{(['MINUTE_INTERVAL', 'INTERVAL', 'HOURLY', 'DAILY', 'MULTI_HOUR', 'WEEKLY', 'MONTHLY', 'CUSTOM'] as const).map(value => <Radio key={value} value={value}>{({ MINUTE_INTERVAL: tr ? 'Dakika aralığı' : 'Minute interval', INTERVAL: tr ? 'Saat aralığı' : 'Hour interval', HOURLY: tr ? 'Saatlik' : 'Hourly', DAILY: tr ? 'Günlük' : 'Daily', MULTI_HOUR: tr ? 'Birden çok saat' : 'Multiple hours', WEEKLY: tr ? 'Haftalık' : 'Weekly', MONTHLY: tr ? 'Aylık' : 'Monthly', CUSTOM: tr ? 'Özel cron' : 'Custom cron' })[value]}</Radio>)}</Radio.Group><div className="schedule-editor-grid">
          {frequency === 'MINUTE_INTERVAL' && <label><span>{tr ? 'Kaç dakikada bir' : 'Every how many minutes'}</span><Input type="number" min={1} max={59} required value={minuteInterval} onChange={e => setMinuteInterval(Number(e.target.value))} /></label>}
          {frequency === 'INTERVAL' && <label><span>{tr ? 'Günlük saat adımı' : 'Daily hour step'}</span><Input type="number" min={1} max={23} required value={intervalHours} onChange={e => setIntervalHours(Number(e.target.value))} /><small>{tr ? 'Cron saatleri her gün 00:00’dan başlar; 24’e bölünmeyen adımlar gece yarısında kısalır.' : 'Cron hours restart at midnight; steps that do not divide 24 have a shorter overnight gap.'}</small></label>}
          {frequency === 'HOURLY' && <label><span>{tr ? 'Saatin kaçıncı dakikası' : 'Minute of the hour'}</span><Input type="number" min={0} max={59} required value={minute} onChange={e => setMinute(Number(e.target.value))} /></label>}
          {frequency === 'MULTI_HOUR' && <><label><span>{tr ? 'Dakika' : 'Minute'}</span><Input type="number" min={0} max={59} required value={minute} onChange={e => setMinute(Number(e.target.value))} /></label><div className="schedule-editor-wide"><span>{tr ? 'Saatler' : 'Hours'}</span><div className="schedule-weekdays" role="group" aria-label={tr ? 'Çalışma saatleri' : 'Run hours'}>{Array.from({ length: 24 }, (_, value) => <label key={value}><input type="checkbox" checked={multipleHours.includes(value)} onChange={e => setMultipleHours(current => e.target.checked ? [...current, value].sort((a, b) => a - b) : current.length > 1 ? current.filter(item => item !== value) : current)} /> {pad(value)}:00</label>)}</div></div></>}
          {['DAILY', 'WEEKLY', 'MONTHLY'].includes(frequency) && <label><span>{tr ? 'Saat' : 'Time'}</span><Input type="time" required value={timeOfDay} onChange={e => setTimeOfDay(e.target.value)} /></label>}
          {frequency === 'WEEKLY' && <div className="schedule-editor-wide"><span>{tr ? 'Günler' : 'Days'}</span><div className="schedule-weekdays" role="group" aria-label={tr ? 'Çalışma günleri' : 'Run days'}>{(tr ? ['Pazartesi','Salı','Çarşamba','Perşembe','Cuma','Cumartesi','Pazar'] : ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']).map((day, index) => <label key={day}><input type="checkbox" checked={weekdays.includes(index + 1)} onChange={e => setWeekdays(current => e.target.checked ? [...current, index + 1].sort((a, b) => a - b) : current.length > 1 ? current.filter(value => value !== index + 1) : current)} /> {day}</label>)}</div><Button type="button" onClick={() => setWeekdays([1, 2, 3, 4, 5])}>{tr ? 'Hafta içi' : 'Weekdays'}</Button></div>}
          {frequency === 'MONTHLY' && <label><span>{tr ? 'Ayın günü' : 'Day of month'}</span><Input type="number" min={1} max={31} required value={monthday} onChange={e => setMonthday(Number(e.target.value))} /><small>{tr ? 'Bu günün bulunmadığı aylar atlanır.' : 'Months without this day are skipped.'}</small></label>}
          {frequency === 'CUSTOM' && <label className="schedule-editor-wide"><span>{t('scheduleCron')}</span><Input required value={draft.cronExpression} onChange={e => update('cronExpression', e.target.value)} /><small>{t('scheduleCronHelp')}</small></label>}
        </div>
        <label className="schedule-editor-wide"><span>{t('scheduleTimeZone')}</span><Select value={draft.timeZone || defaultZone} onChange={e => update('timeZone', e.target.value)}>{selectableZones.map(zone => <option key={zone} value={zone}>{zone}</option>)}{draft.timeZone && !selectableZones.includes(draft.timeZone) && <option value={draft.timeZone}>{draft.timeZone}</option>}</Select></label>
        <div className="schedule-editor-grid"><label><span>{tr ? 'Başlangıç zamanı (isteğe bağlı)' : 'Start time (optional)'}</span><Input type="datetime-local" value={windowStart} onChange={e => setWindowStart(e.target.value)} /></label><label><span>{tr ? 'Bitiş zamanı (isteğe bağlı)' : 'End time (optional)'}</span><Input type="datetime-local" value={windowEnd} onChange={e => setWindowEnd(e.target.value)} /></label></div>
        <small className="schedule-preview-empty">{tr ? `Saatler ${draft.timeZone || defaultZone} zaman dilimindedir; bitiş anında tetikleme yapılmaz.` : `Times use ${draft.timeZone || defaultZone}; no run fires at the end instant.`}</small>
        <div className="schedule-preview">
          <strong><Sparkles size={14} aria-hidden="true" /> {tr ? 'Sonraki 3 çalışma zamanı' : 'Next 3 run times'}</strong>
          <span className="schedule-preview-description">{describeSchedule(effectiveCron, draft.timeZone || defaultZone, tr)}</span>
          {previewLoading ? <span className="schedule-preview-empty">{tr ? 'Hesaplanıyor…' : 'Calculating…'}</span>
            : <>
                {previewOccurrences.length > 0 && <ul>{previewOccurrences.map(date => <li key={date}>{formatScheduleDate(date, locale, draft.timeZone || defaultZone)}</li>)}</ul>}
                {previewErrors.map(item => <small className="schedule-preview-empty" key={item}>{item}</small>)}
                {previewExhausted && previewErrors.length === 0 && <small className="schedule-preview-empty">{tr ? 'Bu aralıkta başka çalışma yok.' : 'No more runs in this range.'}</small>}
                {previewOccurrences.length === 0 && previewErrors.length === 0 && !previewExhausted && <span className="schedule-preview-empty">{frequency === 'CUSTOM' ? (tr ? 'Geçerli bir cron ifadesi girin.' : 'Enter a valid cron expression.') : (tr ? 'Önizleme hesaplanamadı.' : 'Preview could not be calculated.')}</span>}
              </>}
        </div>
      </fieldset>
      <fieldset disabled={saving}><legend>{tr ? 'Çalışma kuralları' : 'Run rules'}</legend><div className="schedule-editor-grid"><label><span>{t('scheduleConflictPolicy')}</span><Select value={draft.conflictPolicy} onChange={e => update('conflictPolicy', e.target.value as CreateScheduleInput['conflictPolicy'])}><option value="SKIP">{t('conflict_SKIP')}</option><option value="QUEUE">{t('conflict_QUEUE')}</option></Select></label><label><span>{t('scheduleMisfirePolicy')}</span><Select value={draft.misfirePolicy} onChange={e => update('misfirePolicy', e.target.value as CreateScheduleInput['misfirePolicy'])}><option value="SKIP">{t('misfire_SKIP')}</option><option value="RUN_ONCE">{t('misfire_RUN_ONCE')}</option></Select></label><label><span>{tr ? 'Yayın çözümleme' : 'Publication resolution'}</span><Select value={draft.publicationPolicy} onChange={e => update('publicationPolicy', e.target.value as SchedulePublicationPolicy)}><option value="LATEST_ACTIVE">{tr ? 'Son aktif yayın' : 'Latest active publication'}</option><option value="PINNED">{tr ? 'Seçili yayını sabitle' : 'Pin selected publication'}</option></Select><small>{draft.publicationPolicy === 'LATEST_ACTIVE' ? (tr ? 'Seçili yayın dayanak olarak saklanır; çalışmada son aktif yayın çözülür.' : 'The selected publication is the anchor; the latest active publication is resolved at run time.') : (tr ? 'Yalnız seçili yayın çalıştırılır; başka sürüme otomatik geçilmez.' : 'Only the selected publication runs; no automatic version fallback.')}</small></label></div></fieldset>
      <footer className="schedule-editor-actions"><Button type="button" onClick={onClose} disabled={saving}>{tr ? 'Vazgeç' : 'Cancel'}</Button><Button tone="primary" icon={<Save size={16} />} type="submit" busy={saving}>{schedule ? (tr ? 'Kaydet' : 'Save') : t('createSchedule')}</Button></footer>
    </form>
  </RecordDetailDialog>
}
