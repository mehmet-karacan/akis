import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import { Columns3, Filter, GitMerge, Plus, Search, Settings2, Trash2, Undo2, Workflow } from 'lucide-react'
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from 'react'
import { topologyApi, type LogicalSchema, type Submodel } from '../topology/api'
import { ExpressionBuilder, expressionSummary } from './ExpressionBuilder'
import { useDefinitionsI18n } from './i18n'
import { filterMappingRows, MAPPING_PAGE_SIZE, pageCount, safePage } from './mappingUtils'
import type { ColumnMapping, MappingContent, MappingJoin, MappingObjectReference } from './types'
import { MappingFilters } from './MappingFilters'
import { MappingDiagram, type MappingColumnSelection } from './MappingDiagram'
import { MappingColumnInspector, type MappingInspectorContext } from './MappingColumnInspector'
import { MappingDesignAssessment } from './MappingDesignAssessment'
import { MappingKmOptions } from './MappingKmOptions'
import { MappingObjectProperties } from './MappingObjectProperties'
import { expressionColumnsValid, removeMappingObject } from './mappingReferences'
import { mappingCatalogEntry, mappingMetadataNotice, type MappingCatalogEntry as CatalogEntry } from './mappingCatalog'
import { applyColumnSuggestions, nextJoinId, suggestColumnMappings, type MappingSuggestion } from './mappingSuggestions'
import { columnSize } from '../topology/columnPresentation'

interface MappingGridProps {
  projectUuid: string
  value: MappingContent
  onChange: (value: MappingContent) => void
  schemaVersion?: number
  onUpgrade?: () => void
  onEnableKm?: () => void
}

function createSource(sources: MappingObjectReference[]): MappingObjectReference {
  let number = sources.length + 1
  let id = `SOURCE_${number}`
  while (sources.some((source) => source.id === id)) {
    id = `SOURCE_${++number}`
  }
  return { id, alias: `SRC_${number}` }
}

function folderPathFor(folderUuid: string | null | undefined, folders: Submodel[]) {
  const names: string[] = []
  const seen = new Set<string>()
  let current = folders.find(folder => folder.uuid === folderUuid)
  while (current && !seen.has(current.uuid)) {
    names.unshift(current.name)
    seen.add(current.uuid)
    current = folders.find(folder => folder.uuid === current?.parentUuid)
  }
  return names.join(' / ')
}

export function assignDataObjectToMapping(value: MappingContent, objectId: string, entry: CatalogEntry): MappingContent {
  const validColumns = new Set(entry.snapshot?.columns.map(column => column.reference) ?? [])
  return {
    ...value,
    sources: value.sources.map(source => source.id === objectId ? {
      ...source,
      dataObjectUuid: entry.object.uuid,
      schemaSnapshotUuid: entry.snapshot?.uuid,
      alias: source.alias || entry.object.code,
    } : source),
    target: value.target.id === objectId ? { ...value.target, dataObjectUuid: entry.object.uuid, schemaSnapshotUuid: entry.snapshot?.uuid, alias: value.target.alias || entry.object.code } : value.target,
    columnMappings: value.columnMappings.filter(mapping => {
      if (mapping.source?.object === objectId && mapping.source.column && !validColumns.has(mapping.source.column)) return false
      if (mapping.target.object === objectId && mapping.target.column && !validColumns.has(mapping.target.column)) return false
      return expressionColumnsValid(mapping.expression, (object, column) => object !== objectId || validColumns.has(column))
    }),
    joins: value.joins.filter(join => ![join.left, join.right].some(ref => ref.object === objectId && !validColumns.has(ref.column))),
    filters: value.filters.filter(filter => filter.predicate
      ? expressionColumnsValid(filter.predicate, (object, column) => object !== objectId || validColumns.has(column))
      : filter.object !== objectId || validColumns.has(filter.column ?? '')),
  }
}

export function MappingGrid({ projectUuid, value, onChange, schemaVersion = 4, onUpgrade }: MappingGridProps) {
  const { language, t } = useDefinitionsI18n()
  const [view, setView] = useState('diagram')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [catalogError, setCatalogError] = useState(false)
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [catalogRevision, setCatalogRevision] = useState(0)
  const [editingExpression, setEditingExpression] = useState<number | null>(null)
  const [pendingObjectDelete, setPendingObjectDelete] = useState<string | null>(null)
  const [undoValue, setUndoValue] = useState<MappingContent | null>(null)
  const [proposal, setProposal] = useState<{ value: MappingContent; catalog: CatalogEntry[]; suggestions: MappingSuggestion[] } | null>(null)
  const suggestions = proposal?.value === value && proposal.catalog === catalog ? proposal.suggestions : null
  const [selectedColumn, setSelectedColumn] = useState<MappingColumnSelection | null>(null)
  const [selectedObject, setSelectedObject] = useState<string | null>(null)
  const splitRef = useRef<HTMLDivElement>(null)
  const resizeStart = useRef<{ y: number; height: number } | null>(null)
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false)
  const [inspectorMaximized, setInspectorMaximized] = useState(false)
  const [inspectorHeight, setInspectorHeight] = useState(() => {
    try { return Math.max(160, Math.min(560, Number(localStorage.getItem('akis.mapping.inspectorHeight')) || 320)) } catch { return 320 }
  })
  useEffect(() => { try { localStorage.setItem('akis.mapping.inspectorHeight', String(inspectorHeight)) } catch { /* Resizing remains available when storage is disabled. */ } }, [inspectorHeight])
  useEffect(() => {
    const refresh = () => setCatalogRevision(revision => revision + 1)
    window.addEventListener('akis:models-changed', refresh)
    return () => window.removeEventListener('akis:models-changed', refresh)
  }, [])
  useEffect(() => { let active = true; setCatalogError(false); setCatalogLoading(true); setCatalog([]); setLogicalSchemas([]); setSelectedColumn(null); void Promise.all([topologyApi.listModels(projectUuid), topologyApi.listLogicalSchemas(projectUuid)]).then(async ([models, schemas]) => {
    const modelCatalogs = await Promise.all(models.map(async (model) => {
      const [objects, folders] = await Promise.all([topologyApi.listDataObjects(projectUuid, model.uuid), topologyApi.listSubmodels(projectUuid, model.uuid)])
      return Promise.all(objects.map(async (object) => {
        const snapshots = await topologyApi.listSchemaSnapshots(projectUuid, object.uuid)
        return { model, object, folderPath: folderPathFor(object.submodelUuid, folders), snapshot: snapshots[0], snapshots }
      }))
    }))
    if (active) { setCatalog(modelCatalogs.flat()); setLogicalSchemas(schemas) }
  }).catch(() => { if (active) setCatalogError(true) }).finally(() => { if (active) setCatalogLoading(false) }); return () => { active = false } }, [projectUuid, catalogRevision])
  const filteredRows = useMemo(
    () => filterMappingRows(value.columnMappings, query),
    [query, value.columnMappings],
  )
  const currentPage = safePage(page, filteredRows.length)
  const pages = pageCount(filteredRows.length)
  const visibleRows = filteredRows.slice(
    currentPage * MAPPING_PAGE_SIZE,
    (currentPage + 1) * MAPPING_PAGE_SIZE,
  )
  const objects = [...value.sources, value.target]
  const activeObject = objects.find(object => object.id === selectedObject)
  const hasInspector = Boolean(selectedColumn || activeObject)
  useEffect(() => {
    const element = splitRef.current
    if (!element || !hasInspector || inspectorCollapsed) return
    const observer = new ResizeObserver(() => setInspectorHeight(height => Math.max(160, Math.min(element.clientHeight - 170, height))))
    observer.observe(element)
    return () => observer.disconnect()
  }, [hasInspector, inspectorCollapsed, view])
  const sources = value.sources
  const entryFor = (objectId: string) => mappingCatalogEntry(objects.find(item => item.id === objectId), catalog)
  const columnsFor = (objectId: string) => entryFor(objectId)?.snapshot?.columns ?? []
  const diagramColumns = useMemo(() => Object.fromEntries(objects.map(object => [object.id, columnsFor(object.id)])), [catalog, value.sources, value.target])
  const inspectorContext = useMemo<MappingInspectorContext | null>(() => {
    if (!selectedColumn) return null
    const entry = entryFor(selectedColumn.datasetId)
    return {
      selection: selectedColumn,
      model: entry?.model,
      object: entry?.object,
      logicalSchema: logicalSchemas.find(schema => schema.uuid === entry?.model.logicalSchemaUuid),
      folderPath: entry?.folderPath,
    }
  }, [catalog, logicalSchemas, selectedColumn, value.sources, value.target])
  const expressionColumns = sources.flatMap((source) => columnsFor(source.id).map((column) => ({ dataset: source.id, column: column.reference, label: `${source.alias || source.id}.${column.reference} · ${column.producerType}` })))
  const suggestMatches = () => setProposal({ value, catalog, suggestions: suggestColumnMappings(value, diagramColumns) })

  const startInspectorResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    setInspectorMaximized(false)
    resizeStart.current = { y: event.clientY, height: inspectorHeight }
    event.currentTarget.setPointerCapture(event.pointerId)
  }
  const resizeInspector = (height: number) => setInspectorHeight(Math.max(160, Math.min((splitRef.current?.clientHeight ?? window.innerHeight) - 170, height)))

  function updateObject(objectId: string, patch: Partial<MappingObjectReference>) {
    setUndoValue(structuredClone(value))
    onChange({ ...value, sources: value.sources.map(source => source.id === objectId ? { ...source, ...patch } : source), target: value.target.id === objectId ? { ...value.target, ...patch } : value.target })
  }

  const assignDataObject = useCallback((objectId: string, objectUuid: string) => {
    const entry = catalog.find(item => item.object.uuid === objectUuid)
    if (!entry) return
    setUndoValue(structuredClone(value))
    setSelectedColumn(null)
    onChange(assignDataObjectToMapping(value, objectId, entry))
  }, [catalog, onChange, value])

  const removeObject = (objectId: string) => {
    setUndoValue(structuredClone(value))
    onChange(removeMappingObject(value, objectId))
    setSelectedColumn(null); setSelectedObject(null)
  }

  function updateRow(index: number, updater: (row: ColumnMapping) => ColumnMapping) {
    onChange({
      ...value,
      columnMappings: value.columnMappings.map((row, current) =>
        current === index ? updater(row) : row,
      ),
    })
  }

  return (
    <div className="mapping-editor">
      <div className="procedure-detail-body mapping-editor-body">
      <div className="procedure-command-tabs procedure-command-tabs--vertical mapping-view-rail" role="tablist" aria-orientation="vertical" aria-label={language === 'tr' ? 'Arayüz Görünümleri' : 'Interface Views'}>
        {([
          { key: 'diagram', label: language === 'tr' ? 'Mantıksal' : 'Logical', icon: <Workflow size={15} aria-hidden="true" />, ready: Boolean(value.target.dataObjectUuid) && value.sources.some((source) => source.dataObjectUuid) },
          { key: 'columns', label: language === 'tr' ? 'Kolon Eşleşmeleri' : 'Column Mappings', icon: <Columns3 size={15} aria-hidden="true" />, ready: value.columnMappings.length > 0 },
          { key: 'conditions', label: language === 'tr' ? 'Join ve Filtreler' : 'Joins and Filters', icon: value.joins.length ? <GitMerge size={15} aria-hidden="true" /> : <Filter size={15} aria-hidden="true" />, ready: value.joins.length + value.filters.length > 0 },
          { key: 'execution', label: language === 'tr' ? 'Fiziksel' : 'Physical', icon: <Settings2 size={15} aria-hidden="true" />, ready: undefined },
        ] as const).map((item) => <AntActionButton tone="ghost" type="button" key={item.key} role="tab" className={`procedure-tab mapping-tab mapping-tab--${item.key}`} aria-selected={view === item.key} onClick={() => setView(item.key)}>{item.icon}{item.label}{item.ready === undefined ? null : <span className={`procedure-tab-dot${item.ready ? ' is-ready' : ''}`} aria-hidden="true" />}</AntActionButton>)}
      </div>
      <div className="procedure-command-detail mapping-editor-detail">
      {view === 'diagram' && <div className="mapping-diagram-toolbar">
        <span>{language === 'tr' ? 'Nesne başlığını seçerek özelliklerini düzenleyin. Modellerden sürükleyerek kaynak ve hedef atayın.' : 'Select a node heading to edit its properties. Drag model objects onto source and target nodes.'}</span>
        <div className="mapping-inline-actions">
        {undoValue && <AntActionButton tone="secondary" icon={<Undo2 size={15} />} onClick={() => { onChange(undoValue); setUndoValue(null); setSelectedColumn(null); setSelectedObject(null) }}>{t('undo')}</AntActionButton>}
        <AntActionButton tone="primary" icon={<Plus size={16} />} onClick={() => {
          const next = createSource(value.sources); setUndoValue(structuredClone(value)); onChange({ ...value, sources: [...value.sources, next] }); setSelectedObject(next.id); setSelectedColumn(null); setInspectorCollapsed(false)
        }}>{language === 'tr' ? 'Kaynak Ekle' : 'Add Source'}</AntActionButton></div>
      </div>}
      {catalogError && <p className="definition-notice definition-notice--error" role="alert">{t('catalogLoadFailed')}</p>}
      {!catalogLoading && !catalogError && objects.map(object => {
        const notice = mappingMetadataNotice(object, catalog, language === 'tr')
        return notice ? <p key={object.id} className="definition-notice definition-notice--info" role="status"><strong>{object.alias || object.id}: </strong>{notice}</p> : null
      })}
      {pendingObjectDelete && <div className="definition-notice definition-notice--info" role="alert">
        <span>{language === 'tr' ? 'Nesne kaldırıldığında ona bağlı eşlemeler, joinler ve filtreler de kaldırılır.' : 'Removing the object also removes its mappings, joins and filters.'}</span>
        <AntActionButton tone="danger" onClick={() => { removeObject(pendingObjectDelete); setPendingObjectDelete(null) }}>{t('confirmRemove')}</AntActionButton>
        <AntActionButton tone="secondary" onClick={() => setPendingObjectDelete(null)}>{t('cancel')}</AntActionButton>
      </div>}
      {view === 'diagram' && <div ref={splitRef} className={`mapping-design-split${hasInspector && !inspectorCollapsed ? ' has-inspector' : ''}${inspectorMaximized && !inspectorCollapsed ? ' inspector-maximized' : ''}`} style={{ '--mapping-inspector-height': `${inspectorHeight}px` } as CSSProperties}>
        <MappingDiagram projectUuid={projectUuid} onEditJoins={() => setView('conditions')} value={value} columns={diagramColumns} onChange={(next) => { setUndoValue(structuredClone(value)); onChange(next) }} onDropObject={assignDataObject} onSelectObject={objectId => { setSelectedObject(objectId); setSelectedColumn(null); setInspectorCollapsed(false) }} onSelectColumn={(selection) => { setSelectedColumn(selection); setSelectedObject(null); setInspectorCollapsed(false) }} selectedColumn={selectedColumn ? { datasetId: selectedColumn.datasetId, columnReference: selectedColumn.column.reference } : null} />
        {hasInspector && !inspectorCollapsed ? <><div className="mapping-inspector-resizer" role="separator" tabIndex={0} aria-label={language === 'tr' ? 'Özellik panelini yeniden boyutlandır' : 'Resize properties panel'} aria-orientation="horizontal" aria-valuenow={Math.round(inspectorHeight)} onPointerDown={startInspectorResize}
          onPointerMove={event => { if (resizeStart.current) resizeInspector(resizeStart.current.height + resizeStart.current.y - event.clientY) }}
          onPointerUp={event => { resizeStart.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId) }} onPointerCancel={() => { resizeStart.current = null }} onLostPointerCapture={() => { resizeStart.current = null }}
          onKeyDown={event => { if (event.key === 'ArrowUp' || event.key === 'ArrowDown') { event.preventDefault(); resizeInspector(inspectorHeight + (event.key === 'ArrowUp' ? 24 : -24)) } }}><span /></div><div className="mapping-inspector-dock">{inspectorContext ? <MappingColumnInspector projectUuid={projectUuid} context={inspectorContext} value={value} columns={diagramColumns} onChange={(next) => { setUndoValue(structuredClone(value)); onChange(next) }} onClose={() => setInspectorCollapsed(true)} maximized={inspectorMaximized} onMaximize={() => setInspectorMaximized(current => !current)} onAliasChange={(datasetId, alias) => {
          updateObject(datasetId, { alias })
        }} /> : activeObject && <MappingObjectProperties alias={activeObject.alias} name={entryFor(activeObject.id)?.object.name} role={activeObject.id === value.target.id ? 'TARGET' : 'SOURCE'} model={entryFor(activeObject.id)?.model.name} schema={logicalSchemas.find(schema => schema.uuid === entryFor(activeObject.id)?.model.logicalSchemaUuid)?.name} folder={entryFor(activeObject.id)?.folderPath} onAlias={alias => updateObject(activeObject.id, { alias })} onRemove={() => setPendingObjectDelete(activeObject.id)} onClose={() => setInspectorCollapsed(true)} maximized={inspectorMaximized} onMaximize={() => setInspectorMaximized(current => !current)} />}</div></> : hasInspector ? <AntActionButton className="mapping-inspector-reopen" tone="secondary" type="button" onClick={() => setInspectorCollapsed(false)}>{language === 'tr' ? 'Özellikleri Aç' : 'Open Properties'}</AntActionButton> : null}
      </div>}
      <section hidden={view !== 'columns'} className="mapping-section" aria-labelledby="mapping-rows-title">
        <div className="mapping-section-heading mapping-section-heading--wrap">
          <div>
            <h3 id="mapping-rows-title">{t('columnMappings')}</h3>
            <p className="definition-help">{language === 'tr' ? 'Eşlemeleri diyagramda sürükle-bırak ile veya hedef kolon özelliklerinden alias.kolon yazarak yönetin.' : 'Manage mappings in the diagram by drag and drop, or enter alias.column in the target column properties.'}</p>
          </div>
          <AntActionButton tone="secondary" type="button" disabled={catalogLoading || catalogError} onClick={suggestMatches}>{t('suggestMatches')}</AntActionButton>
        </div>
        {suggestions !== null ? <div className="definition-notice definition-notice--info" role="status"><span>{suggestions.length ? t('matchSuggestions', { count: suggestions.length }) : (language === 'tr' ? 'Tekil ve veri tipi uyumlu eşleme bulunamadı. Aynı adlı birden fazla kaynak kolonu varsa kaynak seçimini kolon özelliklerinden yapın.' : 'No unique, type-compatible matches found. If multiple sources have the same column name, select the source in column properties.')}</span>{suggestions.length > 0 && <AntActionButton tone="ghost" type="button" onClick={() => { setUndoValue(structuredClone(value)); onChange(applyColumnSuggestions(value, diagramColumns, suggestions)); setProposal(null) }}>{t('applySuggestions')}</AntActionButton>}<AntActionButton tone="ghost" type="button" onClick={() => setProposal(null)}>{t('cancel')}</AntActionButton></div> : null}
        <div className="mapping-grid-toolbar">
          <label className="definition-search definition-search--compact">
            <Search size={16} aria-hidden="true" />
            <span className="sr-only">{t('rowFilter')}</span>
            <AntInput
              placeholder={t('rowFilterPlaceholder')}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value)
                setPage(0)
              }}
            />
          </label>
          <span className="definition-muted">
            {t('rowsShown', { shown: filteredRows.length, total: value.columnMappings.length })}
          </span>
        </div>
        <div className="mapping-grid-scroll" tabIndex={0} role="region" aria-label={t('columnMappings')}>
          <DataGrid viewControls={false} className="mapping-grid">
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">{t('targetColumn')}</th>
                <th scope="col">{language === 'tr' ? 'Veri Tipi' : 'Data Type'}</th>
                <th scope="col">{language === 'tr' ? 'Uzunluk' : 'Length'}</th>
                <th scope="col">{t('mode')}</th>
                <th scope="col">{t('expression')}</th>
                <th scope="col">{t('sourceDataset')}</th>
                <th scope="col">{t('sourceColumn')}</th>
                <th scope="col">{t('targetDataset')}</th>
                <th scope="col"><span className="sr-only">{t('remove')}</span></th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.map(({ row, index }) => {
                const isExpression = !!row.expression
                const targetColumn = columnsFor(row.target.object).find((column) => column.reference === row.target.column)
                return <Fragment key={index}>
                  <tr data-grid-row={index}>
                    <th scope="row"><span className="procedure-step-badge">{index + 1}</span></th>
                    <td><strong>{row.target.column || (language === 'tr' ? 'Tanımlanmadı' : 'Not defined')}</strong></td>
                    <td><span className="mapping-type-chip">{targetColumn?.producerType ?? '—'}</span></td>
                    <td><span className="definition-muted">{targetColumn ? columnSize(targetColumn) : '—'}</span></td>
                    <td><span className={`procedure-route-chip is-ready procedure-route-chip--${isExpression ? 'target' : 'source'}`}>{isExpression ? t('expression') : t('source')}</span></td>
                    <td>
                      <AntActionButton tone="ghost"
                        type="button"
                        className="mapping-expression-button"
                        data-grid-column="expression"
                        aria-label={`${index + 1} ${t('expression')}`}
                        disabled={!isExpression}
                        onClick={() => setEditingExpression(editingExpression === index ? null : index)}
                      >{isExpression ? expressionSummary(row.expression) ?? t('unsupportedExpression') : (language === 'tr' ? 'Tanımlanmadı' : 'Not defined')}</AntActionButton>
                    </td>
                    <td><code>{value.sources.find(source => source.id === row.source?.object)?.alias || row.source?.object || (language === 'tr' ? 'Tanımlanmadı' : 'Not defined')}</code></td>
                    <td><strong>{row.source?.column || (language === 'tr' ? 'Tanımlanmadı' : 'Not defined')}</strong></td>
                    <td><code>{value.target.id === row.target.object ? value.target.alias : row.target.object}</code></td>
                    <td>
                      <AntActionButton tone="ghost"
                        className="definition-icon-button"
                        data-tone="danger"
                        type="button"
                        aria-label={`${t('remove')} ${index + 1}`}
                        onClick={() =>
                          onChange({
                            ...value,
                            columnMappings: value.columnMappings.filter((_, current) => current !== index),
                          })
                        }
                      >
                        <Trash2 size={15} aria-hidden="true" />
                      </AntActionButton>
                    </td>
                  </tr>{editingExpression === index ? <tr className="mapping-expression-row"><td colSpan={10}><ExpressionBuilder value={row.expression} columns={expressionColumns} onCancel={() => setEditingExpression(null)} onApply={(expression) => { updateRow(index, (current) => ({ target: current.target, expression })); setEditingExpression(null) }} /><p className="definition-help">{t('expressionRuntimeUnsupported')}</p></td></tr> : null}</Fragment>
              })}
            </tbody>
          </DataGrid>
        </div>
        <div className="mapping-pagination" aria-label={t('page', { page: currentPage + 1, pages })}>
          <AntActionButton
            tone="secondary"
            type="button"
            disabled={currentPage === 0}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            {t('previous')}
          </AntActionButton>
          <span>{t('page', { page: currentPage + 1, pages })}</span>
          <AntActionButton
            tone="secondary"
            type="button"
            disabled={currentPage >= pages - 1}
            onClick={() => setPage((current) => Math.min(pages - 1, current + 1))}
          >
            {t('next')}
          </AntActionButton>
        </div>
      </section>

      <section hidden={view !== 'conditions'} className="mapping-section mapping-conditions" aria-labelledby="mapping-conditions-title">
        <div className="mapping-section-heading mapping-section-heading--wrap"><div><h3 id="mapping-conditions-title">{language === 'tr' ? 'Join ve Filtreler' : 'Joins and Filters'}</h3><p className="definition-help">{language === 'tr' ? 'Birden fazla kaynağın ilişkisini ve kaynak ya da genel filtreleri tek sözleşmede tanımlayın.' : 'Define multi-source relationships and source or global filters in one contract.'}</p></div></div>
        <section className="mapping-condition-group"><header><strong className="mapping-condition-title"><span className="procedure-heading-icon procedure-heading-icon--join" aria-hidden="true"><GitMerge size={16} /></span>{language === 'tr' ? 'Joinler' : 'Joins'}<span className="procedure-heading-count">{value.joins.length}</span></strong><AntActionButton tone="primary" type="button" disabled={value.sources.length < 2} onClick={() => {
          const left = value.sources[0]; const right = value.sources[1]
          if (!left || !right) return
          const join: MappingJoin = { id: nextJoinId(value.joins), type: 'INNER', left: { object: left.id, column: columnsFor(left.id)[0]?.reference ?? '' }, right: { object: right.id, column: columnsFor(right.id)[0]?.reference ?? '' } }
          onChange({ ...value, joins: [...value.joins, join] })
        }}><Plus size={15} />{language === 'tr' ? 'Join Ekle' : 'Add Join'}</AntActionButton></header>
          {value.joins.length === 0 ? <p className="definition-muted">{language === 'tr' ? 'İkinci kaynak eklendiğinde join tanımlayabilirsiniz.' : 'Add a second source to define a join.'}</p> : value.joins.map((join, index) => <div className="mapping-condition-row" key={join.id}>
            <FormSelect aria-label={language === 'tr' ? 'Join türü' : 'Join type'} value={join.type} onChange={event => onChange({ ...value, joins: value.joins.map((item, current) => current === index ? { ...item, type: event.target.value as MappingJoin['type'] } : item) })}>{(['INNER', 'LEFT', 'RIGHT', 'FULL'] as const).map(type => <option key={type} value={type}>{type} JOIN</option>)}</FormSelect>
            {(['left', 'right'] as const).map(side => <Fragment key={side}><FormSelect aria-label={`${side} object`} value={join[side].object} onChange={event => onChange({ ...value, joins: value.joins.map((item, current) => current === index ? { ...item, [side]: { object: event.target.value, column: columnsFor(event.target.value)[0]?.reference ?? '' } } : item) })}>{value.sources.map(source => <option key={source.id} value={source.id}>{source.alias}</option>)}</FormSelect><FormSelect aria-label={`${side} column`} value={join[side].column} onChange={event => onChange({ ...value, joins: value.joins.map((item, current) => current === index ? { ...item, [side]: { ...item[side], column: event.target.value } } : item) })}>{columnsFor(join[side].object).map(column => <option key={column.reference} value={column.reference}>{column.reference}</option>)}</FormSelect></Fragment>)}
            <AntActionButton tone="ghost" className="definition-icon-button" data-tone="danger" aria-label={language === 'tr' ? 'Join kaldır' : 'Remove join'} onClick={() => onChange({ ...value, joins: value.joins.filter((_, current) => current !== index) })}><Trash2 size={15} /></AntActionButton>
          </div>)}
        </section>
        <MappingFilters projectUuid={projectUuid} value={value} columns={diagramColumns} onChange={onChange} />
      </section>

      <section hidden={view !== 'execution'} className="mapping-section mapping-strategy" aria-labelledby="strategy-title">
        <h3 id="strategy-title">{language === 'tr' ? 'Yürütme Modülleri ve Seçenekler' : 'Execution Modules and Options'}</h3>
        <p className="definition-help">{language === 'tr' ? 'Yazma davranışı seçilen IKM tarafından belirlenir. Anahtar kolonlar ve diğer ayarlar modül seçeneklerinden gelir.' : 'The selected IKM determines write behavior. Key columns and other settings come from module options.'}</p>
        {schemaVersion >= 3 ? <MappingKmOptions projectUuid={projectUuid} value={value} onChange={onChange} /> : <MappingDesignAssessment projectUuid={projectUuid} value={value} schemaVersion={schemaVersion} onUpgrade={onUpgrade} />}
      </section>
      </div></div>
    </div>
  )
}
