import { useEffect, useState } from 'react'
import { DataGrid } from '../../core/ui/DataGrid'
import { useTranslation } from 'react-i18next'
import { ShieldCheck } from 'lucide-react'
import { topologyApi, type SchemaSnapshotColumn } from '../topology/api'
import { columnSize } from '../topology/columnPresentation'

interface Props {
  columns: SchemaSnapshotColumn[]
  /** When given, the table offers the manual "Sensitive" marking; marked columns are encrypted whenever this data store is read. */
  protection?: { projectUuid: string; modelUuid: string; objectUuid: string; editable: boolean }
}

export function DataObjectTable({ columns, protection }: Props) {
  const { t } = useTranslation()
  const [sensitive, setSensitive] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!protection) return
    let active = true
    void topologyApi.listSensitiveColumns(protection.projectUuid, protection.modelUuid, protection.objectUuid)
      .then(items => { if (active) setSensitive(new Set(items)) })
      .catch(() => { if (active) setError(t('models.sensitiveLoadFailed')) })
    return () => { active = false }
  }, [protection?.projectUuid, protection?.modelUuid, protection?.objectUuid, t])
  const toggle = async (column: string) => {
    if (!protection?.editable || busy) return
    const next = new Set(sensitive)
    if (next.has(column)) next.delete(column); else next.add(column)
    setBusy(true); setError('')
    try { setSensitive(new Set(await topologyApi.replaceSensitiveColumns(protection.projectUuid, protection.modelUuid, protection.objectUuid, [...next]))) }
    catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }
  return <div className="model-table-wrap">
    {protection && <p className="model-protection-hint"><ShieldCheck size={14} aria-hidden="true" /> {t('models.sensitiveHint')}</p>}
    {error && <div className="definition-notice definition-notice--error" role="alert">{error}</div>}
    <DataGrid><thead><tr><th>{t('models.columnName')}</th><th>{t('models.dataType')}</th><th>{t('models.size')}</th><th>{t('models.nullable')}</th>{protection && <th>{t('models.sensitive')}</th>}</tr></thead>
      <tbody>{columns.map((column) => <tr key={column.reference} className={sensitive.has(column.reference.toUpperCase()) ? 'is-sensitive' : undefined}>
        <td><strong>{column.reference}</strong></td><td><code>{column.producerType}</code></td><td>{columnSize(column)}</td><td>{column.nullable ? t('models.yes') : t('models.no')}</td>
        {protection && <td><label className="model-sensitive-toggle"><input type="checkbox" aria-label={`${t('models.sensitive')}: ${column.reference}`} disabled={!protection.editable || busy} checked={sensitive.has(column.reference.toUpperCase())} onChange={() => void toggle(column.reference.toUpperCase())} /><span>{sensitive.has(column.reference.toUpperCase()) ? t('models.encrypted') : ''}</span></label></td>}
      </tr>)}</tbody>
    </DataGrid></div>
}
