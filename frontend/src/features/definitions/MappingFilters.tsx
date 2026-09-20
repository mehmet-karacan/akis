import { useEffect, useRef, useState } from 'react'
import { Check, Filter, Pencil, Plus, Trash2, X } from 'lucide-react'
import { Button } from '../../core/ui'
import { Select } from '../../core/ui/Select'
import type { SchemaSnapshotColumn } from '../topology/api'
import type { MappingContent, MappingFilter } from './types'
import { definitionsApi } from './api'
import { useDefinitionsI18n } from './i18n'
import { MappingExpressionInput } from './MappingExpressionInput'
import { mappingFilterSql, nextFilterId } from './mappingFilterSql'

interface Props {
  projectUuid: string
  value: MappingContent
  columns: Record<string, SchemaSnapshotColumn[]>
  onChange(value: MappingContent): void
}

export function MappingFilters({ projectUuid, value, columns, onChange }: Props) {
  const tr = useDefinitionsI18n().language === 'tr'
  const [editing, setEditing] = useState<MappingFilter | null>(null)
  const display = (filter: MappingFilter) => { try { return mappingFilterSql(filter, value) } catch { return tr ? 'Desteklenmeyen filtre: yeniden tanımlayın.' : 'Unsupported filter: redefine the condition.' } }
  return <section className="mapping-condition-group mapping-sql-filters" aria-label={tr ? 'Filtreler' : 'Filters'}>
    <header><strong className="mapping-condition-title"><span className="procedure-heading-icon procedure-heading-icon--filter" aria-hidden="true"><Filter size={16} /></span>{tr ? 'Filtreler' : 'Filters'}<span className="procedure-heading-count">{value.filters.length}</span></strong><Button tone="primary" icon={<Plus size={15} />} disabled={!value.sources.length || Boolean(editing)} onClick={() => setEditing({ id: nextFilterId(value.filters), scope: 'SOURCE', object: value.sources[0]!.id })}>{tr ? 'Filtre Ekle' : 'Add Filter'}</Button></header>
    <p className="definition-help">{tr ? 'Kaynak filtreleri join öncesinde, genel filtreler join sonrasında uygulanır. Aynı kapsam içindeki filtreler AND ile birleşir.' : 'Source filters apply before joins; global filters apply after joins. Filters in the same scope are combined with AND.'}</p>
    {!value.filters.length && !editing && <p className="definition-muted">{tr ? 'Filtre tanımlanmadı.' : 'No filters defined.'}</p>}
    {value.filters.map(filter => <article className="mapping-sql-filter-summary" key={filter.id} data-filter-id={filter.id} data-filter-scope={filter.scope}>
      <div><strong>{filter.scope === 'SOURCE' ? value.sources.find(source => source.id === filter.object)?.alias ?? filter.object : tr ? 'Genel' : 'Global'}</strong><code>{display(filter)}</code></div>
      <Button tone="ghost" className="definition-icon-button" data-tone="info" aria-label={`${tr ? 'Filtreyi düzenle' : 'Edit filter'} ${filter.id}`} disabled={Boolean(editing)} onClick={() => setEditing(filter)} icon={<Pencil size={16} />} />
      <Button tone="ghost" className="definition-icon-button" data-tone="danger" aria-label={`${tr ? 'Filtreyi kaldır' : 'Remove filter'} ${filter.id}`} disabled={Boolean(editing)} onClick={() => onChange({ ...value, filters: value.filters.filter(item => item.id !== filter.id) })} icon={<Trash2 size={16} />} />
    </article>)}
    {editing && <MappingFilterEditor key={editing.id} projectUuid={projectUuid} value={value} columns={columns} filter={editing} onCancel={() => setEditing(null)} onApply={filter => {
      const exists = value.filters.some(item => item.id === filter.id)
      onChange({ ...value, filters: exists ? value.filters.map(item => item.id === filter.id ? filter : item) : [...value.filters, filter] })
      setEditing(null)
    }} />}
  </section>
}

export function MappingFilterEditor({ projectUuid, value, columns, filter, onApply, onCancel }: Omit<Props, 'onChange'> & {
  filter: MappingFilter; onApply(filter: MappingFilter): void; onCancel(): void
}) {
  const tr = useDefinitionsI18n().language === 'tr'
  const [scope, setScope] = useState(filter.scope)
  const [object, setObject] = useState(filter.object)
  const [text, setText] = useState(() => { try { return filter.predicate || filter.operator ? mappingFilterSql(filter, value) : '' } catch { return '' } })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const revision = useRef(0)
  const invalidate = () => { revision.current++; setBusy(false); setError('') }
  useEffect(() => { revision.current++; setBusy(false); return () => { revision.current++ } }, [value, columns, projectUuid])
  const allowed = { ...value, sources: value.sources.filter(source => scope === 'GLOBAL' || source.id === object) }
  const apply = async () => {
    const request = ++revision.current
    setBusy(true); setError('')
    try {
      const result = await definitionsApi.compileMappingExpression(projectUuid, { sql: text, predicate: true, sources: allowed.sources.map(source => ({ object: source.id, alias: source.alias, columns: (columns[source.id] ?? []).map(column => column.reference) })) })
      if (request === revision.current) onApply({ id: filter.id, scope, object, predicate: result.expression })
    } catch (failure) { if (request === revision.current) setError(failure instanceof Error ? failure.message : String(failure)) }
    finally { if (request === revision.current) setBusy(false) }
  }
  return <section className="mapping-filter-editor" aria-label={tr ? 'Filtre Düzenleyici' : 'Filter Editor'}>
    <div className="mapping-filter-editor-context">
      <label><span>{tr ? 'Uygulama Yeri' : 'Apply At'}</span><Select value={scope} aria-label={tr ? 'Filtre kapsamı' : 'Filter scope'} onChange={event => { invalidate(); setScope(event.target.value as MappingFilter['scope']) }}><option value="SOURCE">{tr ? 'Kaynak: Join Öncesi' : 'Source: Before Join'}</option><option value="GLOBAL">{tr ? 'Genel: Join Sonrası' : 'Global: After Join'}</option></Select></label>
      {scope === 'SOURCE' && <label><span>{tr ? 'Kaynak' : 'Source'}</span><Select aria-label={tr ? 'Filtre kaynağı' : 'Filter source'} value={object} onChange={event => { invalidate(); setObject(event.target.value) }}>{value.sources.map(source => <option key={source.id} value={source.id}>{source.alias}</option>)}</Select></label>}
    </div>
    <label className="mapping-filter-editor-label">{tr ? 'SQL Koşulu' : 'SQL Condition'}</label>
    <MappingExpressionInput label={tr ? 'SQL Koşulu' : 'SQL Condition'} text={text} onChange={next => { invalidate(); setText(next) }} value={allowed} columns={columns} />
    <p className="definition-help">{tr ? 'WHERE yazmadan koşulu girin. Alias sonrası nokta kolonları önerir. Örnek:' : 'Enter a condition without WHERE. Type an alias followed by a dot for column suggestions. Example:'} <code>{allowed.sources[0]?.alias ?? 'SRC'}.ID &gt; 10</code></p>
    {error && <p role="alert" className="definition-error">{error}</p>}
    <footer><Button icon={<X size={16} />} onClick={onCancel}>{tr ? 'Vazgeç' : 'Cancel'}</Button><Button tone="primary" icon={<Check size={16} />} disabled={busy || !text.trim() || !allowed.sources.length} onClick={() => void apply()}>{busy ? (tr ? 'Kontrol Ediliyor' : 'Checking') : (tr ? 'Filtreyi Uygula' : 'Apply Filter')}</Button></footer>
  </section>
}
