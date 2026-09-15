import { useTranslation } from 'react-i18next'
import { RecordFieldIcon } from './RecordFieldIcon'
import type { RecordAudit } from './useRecordAudit'

export function RecordAuditFields({ record, state = 'ready' }: { record?: RecordAudit; state?: 'loading' | 'ready' | 'error' }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const missing = state === 'loading' ? (tr ? 'Yükleniyor…' : 'Loading…') : state === 'error' ? (tr ? 'Bilgi alınamadı' : 'Unavailable') : (tr ? 'Kaydedilmemiş' : 'Not Recorded')
  const date = (value?: string | null) => value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : missing
  const fields = [
    [tr ? 'Oluşturan' : 'Created By', record?.createdBy || missing],
    [tr ? 'Oluşturulma Zamanı' : 'Created At', date(record?.createdAt)],
    [tr ? 'Güncelleyen' : 'Updated By', record?.updatedBy || missing],
    [tr ? 'Güncellenme Zamanı' : 'Updated At', date(record?.updatedAt)],
  ]
  return <dl className="ui-record-audit">{fields.map(([label, value]) => <div key={label}><dt><RecordFieldIcon label={label} />{label}</dt><dd>{value}</dd></div>)}</dl>
}
