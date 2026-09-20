import type { RecordAudit } from './useRecordAudit'

export function recordAuditPresentation(record: RecordAudit | undefined, state: 'loading' | 'ready' | 'error', locale: string) {
  const tr = locale.startsWith('tr')
  const missing = state === 'loading' ? (tr ? 'Yükleniyor…' : 'Loading…') : state === 'error' ? (tr ? 'Bilgi alınamadı' : 'Unavailable') : (tr ? 'Kaydedilmemiş' : 'Not Recorded')
  const date = (value?: string | null) => value ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : missing
  return { createdBy: record?.createdBy || missing, createdAt: date(record?.createdAt), updatedBy: record?.updatedBy || missing, updatedAt: date(record?.updatedAt) }
}
