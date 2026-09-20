import { Input as AntInput } from 'antd'
import { Database, DatabaseZap, FolderTree, Link2, Maximize2, Minimize2, Network, Table2, Unlink, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Button } from '../../core/ui'
import type { DataObject, LogicalSchema, Model, SchemaSnapshotColumn } from '../topology/api'
import type { MappingColumnSelection } from './MappingDiagram'
import type { MappingContent } from './types'
import { useDefinitionsI18n } from './i18n'
import { definitionsApi } from './api'
import { MappingExpressionInput } from './MappingExpressionInput'
import { mappingSqlText } from './mappingSqlText'
import { columnSize } from '../topology/columnPresentation'
import { typesCompatible } from './mappingColumnCompatibility'

export interface MappingInspectorContext {
  selection: MappingColumnSelection
  model?: Model
  object?: DataObject
  logicalSchema?: LogicalSchema
  folderPath?: string
}

export interface SourceColumnReference {
  datasetId: string
  column: string
}

export function resolveSourceColumnReference(
  input: string,
  value: MappingContent,
  columns: Record<string, SchemaSnapshotColumn[]>,
): SourceColumnReference | null {
  const separator = input.lastIndexOf('.')
  if (separator <= 0 || separator === input.length - 1) return null
  const alias = input.slice(0, separator).trim().toLocaleUpperCase()
  const reference = input.slice(separator + 1).trim().toLocaleUpperCase()
  const dataset = value.sources.find(item => [item.id, item.alias].some(candidate => candidate.toLocaleUpperCase() === alias))
  if (!dataset) return null
  const column = columns[dataset.id]?.find(item => item.reference.toLocaleUpperCase() === reference)
  return column ? { datasetId: dataset.id, column: column.reference } : null
}

export function mapTargetFromSourceReference(
  value: MappingContent,
  targetDatasetId: string,
  targetColumn: string,
  source: SourceColumnReference,
): MappingContent {
  const mapping = {
    source: { object: source.datasetId, column: source.column },
    target: { object: targetDatasetId, column: targetColumn },
  }
  const existingIndex = value.columnMappings.findIndex(row => row.target.object === targetDatasetId && row.target.column === targetColumn)
  return {
    ...value,
    columnMappings: existingIndex < 0
      ? [...value.columnMappings, mapping]
      : value.columnMappings.map((row, index) => index === existingIndex ? mapping : row),
  }
}

export function MappingColumnInspector({ projectUuid, context, value, columns, onChange, onClose, onMaximize, maximized = false, onAliasChange }: {
  projectUuid: string
  context: MappingInspectorContext
  value: MappingContent
  columns: Record<string, SchemaSnapshotColumn[]>
  onChange(value: MappingContent): void
  onClose(): void
  onMaximize(): void
  maximized?: boolean
  onAliasChange(datasetId: string, alias: string): void
}) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const { selection, model, object, logicalSchema, folderPath } = context
  const objects = [...value.sources, value.target]
  const dataset = objects.find(item => item.id === selection.datasetId)
  const currentMapping = value.columnMappings.find(row => row.target.object === selection.datasetId && row.target.column === selection.column.reference)
  const currentSourceDataset = value.sources.find(item => item.id === currentMapping?.source?.object)
  let currentExpression = '', unreadableExpression = false
  try {
    currentExpression = currentMapping?.source?.column
      ? `${currentSourceDataset?.alias || currentSourceDataset?.id}.${currentMapping.source.column}`
      : mappingSqlText(currentMapping?.expression, Object.fromEntries(value.sources.map(source => [source.id, source.alias])))
  } catch { unreadableExpression = true }
  const [sourceExpression, setSourceExpression] = useState(currentExpression)
  const [error, setError] = useState('')
  const revision = useRef(0)
  const [busy, setBusy] = useState(false)
  useEffect(() => { revision.current++; setBusy(false); return () => { revision.current++ } }, [value, columns, selection.datasetId, selection.column.reference, projectUuid])
  useEffect(() => { setSourceExpression(currentExpression); setError('') }, [currentExpression, selection.datasetId, selection.column.reference])
  const size = columnSize(selection.column)

  const applyMapping = async () => {
    const source = resolveSourceColumnReference(sourceExpression, value, columns)
    if (source) {
      const sourceColumn = columns[source.datasetId]?.find(column => column.reference === source.column)
      if (!typesCompatible(sourceColumn?.canonicalType, selection.column.canonicalType)) {
        setError(tr ? 'Kaynak ve hedef veri tipleri doğrudan eşlemeye uygun değil. TO_CHAR veya TO_NUMBER gibi açık bir SQL dönüşümü kullanın.' : 'Source and target data types are incompatible for direct mapping. Use an explicit SQL conversion such as TO_CHAR or TO_NUMBER.')
        return
      }
      revision.current++; setBusy(false); setError('')
      onChange(mapTargetFromSourceReference(value, selection.datasetId, selection.column.reference, source)); return
    }
    const request = ++revision.current
    setBusy(true); setError('')
    try {
      const result = await definitionsApi.compileMappingExpression(projectUuid, { sql: sourceExpression, sources: value.sources.map(item => ({ object: item.id, alias: item.alias, columns: (columns[item.id] ?? []).map(column => column.reference) })) })
      if (request !== revision.current) return
      const mapping = { expression: result.expression, target: { object: selection.datasetId, column: selection.column.reference } }
      const index = value.columnMappings.findIndex(row => row.target.object === selection.datasetId && row.target.column === selection.column.reference)
      onChange({ ...value, columnMappings: index < 0 ? [...value.columnMappings, mapping] : value.columnMappings.map((row, rowIndex) => rowIndex === index ? mapping : row) })
    } catch (failure) { if (request === revision.current) setError(failure instanceof Error ? failure.message : String(failure)) }
    finally { if (request === revision.current) setBusy(false) }
  }
  const removeMapping = () => {
    revision.current++; setBusy(false); setError('')
    onChange({
      ...value,
      columnMappings: value.columnMappings.filter(row => !(row.target.object === selection.datasetId && row.target.column === selection.column.reference)),
    })
  }

  return <section className="mapping-column-inspector" aria-label={tr ? 'Kolon Özellikleri' : 'Column Properties'}>
    <header>
      <div className="mapping-inspector-identity"><span className={`procedure-heading-icon procedure-heading-icon--${selection.role.toLowerCase()}`} aria-hidden="true">{selection.role === 'SOURCE' ? <DatabaseZap size={17} /> : <Database size={17} />}</span><div><span>{selection.role === 'SOURCE' ? (tr ? 'Kaynak Kolon' : 'Source Column') : (tr ? 'Hedef Kolon' : 'Target Column')}</span><strong>{selection.column.reference}</strong></div></div>
      <div className="mapping-inspector-actions"><Button tone="ghost" className="definition-icon-button" aria-label={maximized ? (tr ? 'Önceki boyuta dön' : 'Restore panel') : (tr ? 'Paneli büyüt' : 'Maximize panel')} onClick={onMaximize}>{maximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}</Button><Button tone="ghost" className="definition-icon-button" aria-label={tr ? 'Özellikleri daralt' : 'Collapse properties'} onClick={onClose}><X size={16} /></Button></div>
    </header>
    <div className="mapping-inspector-context">
      <dl>
        <div><dt><Network size={15} /> {tr ? 'Mantıksal Şema' : 'Logical Schema'}</dt><dd>{logicalSchema?.name ?? (tr ? 'Tanımlanmadı' : 'Not defined')}</dd></div>
        <div><dt><Database size={15} /> Model</dt><dd>{model?.name ?? (tr ? 'Tanımlanmadı' : 'Not defined')}</dd></div>
        <div><dt><Table2 size={15} /> Data Store</dt><dd>{object?.name ?? (tr ? 'Tanımlanmadı' : 'Not defined')}<small>{object?.objectReference}</small></dd></div>
        <div><dt><FolderTree size={15} /> {tr ? 'Klasör' : 'Folder'}</dt><dd>{folderPath || (tr ? 'Model kökü' : 'Model root')}</dd></div>
        <div><dt>{tr ? 'Veri Tipi' : 'Data Type'}</dt><dd>{selection.column.producerType}</dd></div>
        <div><dt>{tr ? 'Uzunluk / Hassasiyet' : 'Length / Precision'}</dt><dd>{size}</dd></div>
      </dl>
      <label className="mapping-inspector-alias"><span>Data Store Alias</span><AntInput value={dataset?.alias ?? ''} onChange={event => onAliasChange(selection.datasetId, event.target.value)} /></label>
    </div>
    {selection.role === 'TARGET' ? <div className="mapping-inspector-mapping">
      <div className="mapping-expression-field"><span>{tr ? 'Kaynak Eşleme' : 'Source Mapping'}</span><MappingExpressionInput label={tr ? 'Kaynak Eşleme' : 'Source Mapping'} text={sourceExpression} value={value} columns={columns} onChange={text => { revision.current++; setBusy(false); setSourceExpression(text); setError('') }} /></div>
      <Button tone="primary" icon={<Link2 size={16} />} disabled={busy || !sourceExpression.trim()} onClick={() => void applyMapping()}>{busy ? (tr ? 'Kontrol Ediliyor' : 'Checking') : (tr ? 'Eşlemeyi Uygula' : 'Apply Mapping')}</Button>
      <Button tone="secondary" icon={<Unlink size={16} />} disabled={!currentMapping} onClick={removeMapping}>{tr ? 'Eşlemeyi Kaldır' : 'Remove Mapping'}</Button>
      {error || (unreadableExpression && !sourceExpression) ? <p role="alert">{error || (tr ? 'Mevcut ifade görüntülenemiyor. Eşleme korunuyor; değiştirmek için yeni bir SQL ifadesi girin.' : 'The existing expression cannot be displayed. The mapping is preserved; enter a new SQL expression to replace it.')}</p> : <small>{tr ? 'SQL değer ifadesi yazın. Alias sonrası nokta kolonları önerir. Örnek:' : 'Enter a SQL value expression. A dot after an alias suggests columns. Example:'} <code>TO_CHAR(SRC.CREATED_AT, 'YYYY-MM-DD')</code>. {tr ? 'Uygula eşlemeyi günceller; kalıcı kayıt için arayüzü kaydedin.' : 'Apply updates the mapping; save the interface to persist it.'}</small>}
    </div> : <p className="mapping-inspector-source-hint">{tr ? <>Bu kolonu hedef kolona sürükleyerek bağlayabilir veya hedef kolonu seçip <code>alias.kolon</code> biçiminde eşleyebilirsiniz.</> : <>Drag this column to a target column, or select a target column and map it using <code>alias.column</code>.</>}</p>}
  </section>
}
