import { useTranslation } from 'react-i18next'
import { RecordFieldIcon } from './RecordFieldIcon'
import type { RecordAudit } from './useRecordAudit'
import { recordAuditPresentation } from './recordAuditPresentation'

export function RecordAuditFields({ record, state = 'ready' }: { record?: RecordAudit; state?: 'loading' | 'ready' | 'error' }) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const values = recordAuditPresentation(record, state, i18n.language)
  const fields = [
    [tr ? 'Oluşturan' : 'Created By', values.createdBy],
    [tr ? 'Oluşturulma Zamanı' : 'Created At', values.createdAt],
    [tr ? 'Güncelleyen' : 'Updated By', values.updatedBy],
    [tr ? 'Güncellenme Zamanı' : 'Updated At', values.updatedAt],
  ]
  return <dl className="ui-record-audit">{fields.map(([label, value]) => <div key={label}><dt><RecordFieldIcon label={label} />{label}</dt><dd>{value}</dd></div>)}</dl>
}
