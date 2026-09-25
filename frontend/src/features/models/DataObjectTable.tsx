import { useEffect, useMemo, useState } from 'react'
import { Input } from 'antd'
import { DataGrid } from '../../core/ui/DataGrid'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Columns3, Search, ShieldCheck } from 'lucide-react'
import { topologyApi, type SchemaSnapshotColumn } from '../topology/api'
import { columnSize } from '../topology/columnPresentation'

interface Props {
  columns: SchemaSnapshotColumn[]
  /** When given, the table offers the manual "Sensitive" marking; marked columns are encrypted whenever this data store is read. */
  protection?: { projectUuid: string; modelUuid: string; objectUuid: string; editable: boolean }
}

export function DataObjectTable({ columns, protection }: Props) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const [sensitive, setSensitive] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
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
  const filtered = useMemo(() => {
    const text = query.trim().toLocaleLowerCase(i18n.language)
    return text ? columns.filter(column => `${column.reference} ${column.producerType} ${column.canonicalType}`.toLocaleLowerCase(i18n.language).includes(text)) : columns
  }, [columns, i18n.language, query])
  const required = columns.filter(column => !column.nullable).length

  return <section className="model-column-catalog" aria-labelledby="model-column-title">
    <header className="model-column-header">
      <div><span className="model-column-kicker">{tr ? 'Yapı ve koruma' : 'Structure and protection'}</span><h3 id="model-column-title"><Columns3 size={18} />{tr ? 'Kolon Kataloğu' : 'Column Catalog'}</h3><p>{tr ? 'Kolon tiplerini, zorunluluk durumunu ve hassas veri işaretlerini tek ekrandan inceleyin.' : 'Review column types, nullability, and sensitive-data markings in one place.'}</p></div>
      <label className="model-column-search"><span className="sr-only">{tr ? 'Kolon ara' : 'Search columns'}</span><Input allowClear prefix={<Search size={15} />} placeholder={tr ? 'Kolon adı veya veri tipi ara' : 'Search column name or data type'} value={query} onChange={event => setQuery(event.target.value)} /></label>
    </header>
    <div className="model-column-summary" aria-label={tr ? 'Kolon özeti' : 'Column summary'}>
      <div><Columns3 size={17} /><span>{tr ? 'Toplam Kolon' : 'Total Columns'}<strong>{columns.length}</strong></span></div>
      <div><CheckCircle2 size={17} /><span>{tr ? 'Zorunlu' : 'Required'}<strong>{required}</strong></span></div>
      <div><span className="model-column-nullable-mark">?</span><span>{tr ? 'Null Olabilir' : 'Nullable'}<strong>{columns.length - required}</strong></span></div>
      {protection && <div><ShieldCheck size={17} /><span>{tr ? 'Hassas' : 'Sensitive'}<strong>{sensitive.size}</strong></span></div>}
    </div>
    <div className="model-table-wrap">
    {protection && <p className="model-protection-hint"><ShieldCheck size={14} aria-hidden="true" /> {t('models.sensitiveHint')}</p>}
    {error && <div className="definition-notice definition-notice--error" role="alert">{error}</div>}
    <DataGrid collectionTitle={tr ? 'Kolon Listesi' : 'Column List'} collectionIcon={<Columns3 />} cardHeaderField="type" cardHiddenFields={['type']}><thead><tr><th data-field-key="name">{t('models.columnName')}</th><th data-field-key="type">{t('models.dataType')}</th><th data-field-key="size">{t('models.size')}</th><th data-field-key="nullable">{t('models.nullable')}</th>{protection && <th data-field-key="sensitive">{t('models.sensitive')}</th>}</tr></thead>
      <tbody>{filtered.map((column) => <tr key={column.reference} className={sensitive.has(column.reference.toUpperCase()) ? 'is-sensitive' : undefined}>
        <td><strong>{column.reference}</strong></td><td><code>{column.producerType}</code></td><td>{columnSize(column)}</td><td>{column.nullable ? t('models.yes') : t('models.no')}</td>
        {protection && <td><label className="model-sensitive-toggle"><input type="checkbox" aria-label={`${t('models.sensitive')}: ${column.reference}`} disabled={!protection.editable || busy} checked={sensitive.has(column.reference.toUpperCase())} onChange={() => void toggle(column.reference.toUpperCase())} /><span>{sensitive.has(column.reference.toUpperCase()) ? t('models.encrypted') : ''}</span></label></td>}
      </tr>)}</tbody>
    </DataGrid>
    {filtered.length === 0 && <p className="model-column-empty">{tr ? 'Aramayla eşleşen kolon bulunamadı.' : 'No columns match your search.'}</p>}
    </div>
  </section>
}
