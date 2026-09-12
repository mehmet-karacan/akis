import { Plus, Search, Trash2, Undo2 } from 'lucide-react'
import { Fragment, useEffect, useMemo, useState, type KeyboardEvent } from 'react'
import { topologyApi, type DataObject, type Model, type SchemaSnapshot } from '../topology/api'
import { ExpressionBuilder, expressionSummary } from './ExpressionBuilder'
import { useDefinitionsI18n } from './i18n'
import { filterMappingRows, MAPPING_PAGE_SIZE, pageCount, safePage } from './mappingUtils'
import type { ColumnMapping, MappingContent, MappingDataset } from './types'

interface MappingGridProps {
  projectUuid: string
  value: MappingContent
  onChange: (value: MappingContent) => void
}

function createDataset(role: 'SOURCE' | 'TARGET', datasets: MappingDataset[]): MappingDataset {
  let number = datasets.filter((dataset) => dataset.role === role).length + 1
  let id = `${role}_${number}`
  while (datasets.some((dataset) => dataset.id === id)) {
    id = `${role}_${++number}`
  }
  return { id, role, name: role === 'SOURCE' ? 'Source' : 'Target' }
}

function createRow(value: MappingContent): ColumnMapping {
  const source = value.datasets.find((dataset) => dataset.role === 'SOURCE')?.id ?? ''
  const target = value.datasets.find((dataset) => dataset.role === 'TARGET')?.id ?? ''
  return { source: { dataset: source, column: '' }, target: { dataset: target, column: '' } }
}

interface CatalogEntry { model: Model; object: DataObject; snapshot?: SchemaSnapshot }

export function MappingGrid({ projectUuid, value, onChange }: MappingGridProps) {
  const { t } = useDefinitionsI18n()
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [catalogError, setCatalogError] = useState(false)
  const [editingExpression, setEditingExpression] = useState<number | null>(null)
  const [pendingDatasetDelete, setPendingDatasetDelete] = useState<number | null>(null)
  const [undoValue, setUndoValue] = useState<MappingContent | null>(null)
  const [suggestions, setSuggestions] = useState<Array<{ index: number; dataset: string; column: string }>>([])
  useEffect(() => { let active = true; setCatalogError(false); void topologyApi.listModels(projectUuid).then(async (models) => { const objects = (await Promise.all(models.map(async (model) => (await topologyApi.listDataObjects(projectUuid, model.uuid)).map((object) => ({ model, object }))))).flat(); const rows = await Promise.all(objects.map(async (entry) => ({ ...entry, snapshot: (await topologyApi.listSchemaSnapshots(projectUuid, entry.object.uuid))[0] }))); if (active) setCatalog(rows) }).catch(() => { if (active) setCatalogError(true) }); return () => { active = false } }, [projectUuid])
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
  const sources = value.datasets.filter((dataset) => dataset.role === 'SOURCE')
  const targets = value.datasets.filter((dataset) => dataset.role === 'TARGET')
  const entryFor = (datasetId: string) => { const dataset = value.datasets.find((item) => item.id === datasetId); return catalog.find((entry) => entry.object.uuid === dataset?.dataObjectUuid) }
  const columnsFor = (datasetId: string) => entryFor(datasetId)?.snapshot?.columns ?? []
  const expressionColumns = sources.flatMap((dataset) => columnsFor(dataset.id).map((column) => ({ dataset: dataset.id, column: column.reference, label: `${dataset.name || dataset.id}.${column.reference} · ${column.producerType}` })))
  const suggestMatches = () => setSuggestions(value.columnMappings.flatMap((row, index) => { if (row.expression || row.source?.column || !row.target.column) return []; const match = sources.flatMap((dataset) => columnsFor(dataset.id).map((column) => ({ dataset: dataset.id, column: column.reference }))).find((candidate) => candidate.column.toLocaleUpperCase() === row.target.column.toLocaleUpperCase()); return match ? [{ index, ...match }] : [] }))

  function updateDataset(index: number, patch: Partial<MappingDataset>) {
    const datasets = value.datasets.map((dataset, current) =>
      current === index ? { ...dataset, ...patch } : dataset,
    )
    onChange({ ...value, datasets })
  }

  function updateRow(index: number, updater: (row: ColumnMapping) => ColumnMapping) {
    onChange({
      ...value,
      columnMappings: value.columnMappings.map((row, current) =>
        current === index ? updater(row) : row,
      ),
    })
  }

  function handleGridKeyDown(event: KeyboardEvent<HTMLTableElement>) {
    if (event.key !== 'Enter' || event.ctrlKey || event.metaKey) return
    const input = event.target as HTMLElement
    const column = input.dataset.gridColumn
    const row = input.closest<HTMLTableRowElement>('tr[data-grid-row]')
    if (!column || !row) return
    const sibling = event.shiftKey ? row.previousElementSibling : row.nextElementSibling
    const nextInput = sibling?.querySelector<HTMLElement>(`[data-grid-column="${column}"]`)
    if (nextInput) {
      event.preventDefault()
      nextInput.focus()
    }
  }

  return (
    <div className="mapping-editor">
      <section className="mapping-section" aria-labelledby="datasets-title">
        <div className="mapping-section-heading">
          <h3 id="datasets-title">{t('datasets')}</h3>
          <div className="mapping-inline-actions">
            <button
              className="definition-button definition-button--quiet"
              type="button"
              onClick={() =>
                onChange({ ...value, datasets: [...value.datasets, createDataset('SOURCE', value.datasets)] })
              }
            >
              <Plus size={16} aria-hidden="true" /> {t('source')}
            </button>
            <button
              className="definition-button definition-button--quiet"
              type="button"
              onClick={() =>
                onChange({ ...value, datasets: [...value.datasets, createDataset('TARGET', value.datasets)] })
              }
            >
              <Plus size={16} aria-hidden="true" /> {t('target')}
            </button>
          </div>
        </div>
        {catalogError ? <p className="definition-notice definition-notice--error" role="alert">{t('catalogLoadFailed')}</p> : null}
        {pendingDatasetDelete != null ? <div className="definition-notice definition-notice--info" role="alert"><span>{t('datasetDeleteImpact', { count: value.columnMappings.filter((row) => row.source?.dataset === value.datasets[pendingDatasetDelete]?.id || row.target.dataset === value.datasets[pendingDatasetDelete]?.id || expressionSummary(row.expression)?.includes(`${value.datasets[pendingDatasetDelete]?.id}.`)).length })}</span><button type="button" onClick={() => { const dataset = value.datasets[pendingDatasetDelete]; if (!dataset) return; setUndoValue(structuredClone(value)); onChange({ ...value, datasets: value.datasets.filter((_, position) => position !== pendingDatasetDelete), columnMappings: value.columnMappings.filter((row) => row.source?.dataset !== dataset.id && row.target.dataset !== dataset.id && !expressionSummary(row.expression)?.includes(`${dataset.id}.`)) }); setPendingDatasetDelete(null) }}>{t('confirmRemove')}</button><button type="button" onClick={() => setPendingDatasetDelete(null)}>{t('cancel')}</button></div> : null}
        <div className="mapping-datasets">
          {value.datasets.map((dataset, index) => (
            <div className="mapping-dataset" key={index}>
              <span className={`definition-role definition-role--${dataset.role.toLowerCase()}`}>
                {dataset.role === 'SOURCE' ? t('source') : t('target')}
              </span>
              <label>
                <span>{t('catalogObject')}</span>
                <select value={dataset.dataObjectUuid ?? ''} onChange={(event) => { const entry = catalog.find((item) => item.object.uuid === event.target.value); updateDataset(index, { dataObjectUuid: entry?.object.uuid, schemaSnapshotUuid: entry?.snapshot?.uuid, name: dataset.name || entry?.object.name }) }}><option value="">{t('chooseDataObject')}</option>{catalog.map((entry) => <option key={entry.object.uuid} value={entry.object.uuid}>{entry.model.name} → {entry.object.name} · {entry.object.objectReference}</option>)}</select>
              </label><label>
                <span>{t('datasetAlias')}</span>
                <input
                  value={dataset.name ?? ''}
                  onChange={(event) => updateDataset(index, { name: event.target.value })}
                />
              </label><code className="dataset-stable-id">{dataset.id}</code>
              <button
                type="button"
                className="definition-icon-button"
                aria-label={`${t('remove')} ${dataset.id}`}
                onClick={() => setPendingDatasetDelete(index)}
              >
                <Trash2 size={16} aria-hidden="true" />
              </button>
            </div>
          ))}
        </div>
        {undoValue ? <button className="definition-button definition-button--quiet mapping-undo" type="button" onClick={() => { onChange(undoValue); setUndoValue(null) }}><Undo2 size={15} />{t('undo')}</button> : null}
      </section>

      <section className="mapping-section" aria-labelledby="mapping-rows-title">
        <div className="mapping-section-heading mapping-section-heading--wrap">
          <div>
            <h3 id="mapping-rows-title">{t('columnMappings')}</h3>
            <p className="definition-help">{t('mappingKeyboardHint')}</p>
          </div>
          <button
            className="definition-button definition-button--quiet"
            type="button"
            onClick={() => {
              const nextRows = [...value.columnMappings, createRow(value)]
              onChange({ ...value, columnMappings: nextRows })
              setQuery('')
              setPage(pageCount(nextRows.length) - 1)
            }}
          >
            <Plus size={16} aria-hidden="true" /> {t('addRow')}
          </button>
          <button className="definition-button definition-button--quiet" type="button" onClick={suggestMatches}>{t('suggestMatches')}</button>
        </div>
        {suggestions.length > 0 ? <div className="definition-notice definition-notice--info"><span>{t('matchSuggestions', { count: suggestions.length })}</span><button type="button" onClick={() => { setUndoValue(structuredClone(value)); onChange({ ...value, columnMappings: value.columnMappings.map((row, index) => { const suggestion = suggestions.find((item) => item.index === index); return suggestion && !row.expression && !row.source?.column ? { ...row, source: { dataset: suggestion.dataset, column: suggestion.column } } : row }) }); setSuggestions([]) }}>{t('applySuggestions')}</button><button type="button" onClick={() => setSuggestions([])}>{t('cancel')}</button></div> : null}
        <div className="mapping-grid-toolbar">
          <label className="definition-search definition-search--compact">
            <Search size={16} aria-hidden="true" />
            <span className="sr-only">{t('rowFilter')}</span>
            <input
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
          <table className="mapping-grid" onKeyDown={handleGridKeyDown}>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">{t('mode')}</th>
                <th scope="col">{t('sourceDataset')}</th>
                <th scope="col">{t('sourceColumn')}</th>
                <th scope="col">{t('expression')}</th>
                <th scope="col">{t('targetDataset')}</th>
                <th scope="col">{t('targetColumn')}</th>
                <th scope="col"><span className="sr-only">{t('remove')}</span></th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.map(({ row, index }) => {
                const isExpression = !!row.expression
                return <Fragment key={index}>
                  <tr data-grid-row={index}>
                    <th scope="row">{index + 1}</th>
                    <td>
                      <select
                        data-grid-column="mode"
                        aria-label={`${index + 1} ${t('mode')}`}
                        value={isExpression ? 'EXPRESSION' : 'SOURCE'}
                        onChange={(event) =>
                          updateRow(index, (current) =>
                            event.target.value === 'EXPRESSION'
                              ? { expression: { kind: 'COLUMN', dataset: sources[0]?.id ?? '', column: columnsFor(sources[0]?.id ?? '')[0]?.reference ?? '' }, target: current.target }
                              : {
                                  source: {
                                    dataset: sources[0]?.id ?? '',
                                    column: '',
                                  },
                                  target: current.target,
                                },
                          )
                        }
                      >
                        <option value="SOURCE">{t('source')}</option>
                        <option value="EXPRESSION">{t('expression')}</option>
                      </select>
                    </td>
                    <td>
                      <select
                        data-grid-column="source-dataset"
                        aria-label={`${index + 1} ${t('sourceDataset')}`}
                        value={row.source?.dataset ?? ''}
                        disabled={isExpression}
                        onChange={(event) =>
                          updateRow(index, (current) => ({
                            ...current,
                            source: { dataset: event.target.value, column: '' },
                          }))
                        }
                      >
                        <option value="">—</option>
                        {sources.map((dataset) => (
                          <option key={dataset.id} value={dataset.id}>{dataset.id}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <select
                        data-grid-column="source-column"
                        aria-label={`${index + 1} ${t('sourceColumn')}`}
                        value={row.source?.column ?? ''}
                        disabled={isExpression}
                        onChange={(event) =>
                          updateRow(index, (current) => ({
                            ...current,
                            source: {
                              dataset: current.source?.dataset ?? sources[0]?.id ?? '',
                              column: event.target.value,
                            },
                          }))
                        }
                      ><option value="">—</option>{columnsFor(row.source?.dataset ?? '').map((column) => <option key={column.reference} value={column.reference}>{column.reference} · {column.producerType}</option>)}</select>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="mapping-expression-button"
                        data-grid-column="expression"
                        aria-label={`${index + 1} ${t('expression')}`}
                        disabled={!isExpression}
                        onClick={() => setEditingExpression(editingExpression === index ? null : index)}
                      >{expressionSummary(row.expression) ?? t('unsupportedExpression')}</button>
                    </td>
                    <td>
                      <select
                        data-grid-column="target-dataset"
                        aria-label={`${index + 1} ${t('targetDataset')}`}
                        value={row.target.dataset}
                        onChange={(event) =>
                          updateRow(index, (current) => ({
                            ...current,
                            target: { dataset: event.target.value, column: '' },
                          }))
                        }
                      >
                        <option value="">—</option>
                        {targets.map((dataset) => (
                          <option key={dataset.id} value={dataset.id}>{dataset.id}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <select
                        data-grid-column="target-column"
                        aria-label={`${index + 1} ${t('targetColumn')}`}
                        value={row.target.column}
                        onChange={(event) =>
                          updateRow(index, (current) => ({
                            ...current,
                            target: { ...current.target, column: event.target.value },
                          }))
                        }
                      ><option value="">—</option>{columnsFor(row.target.dataset).map((column) => <option key={column.reference} value={column.reference}>{column.reference} · {column.producerType}</option>)}</select>
                    </td>
                    <td>
                      <button
                        className="definition-icon-button"
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
                      </button>
                    </td>
                  </tr>{editingExpression === index ? <tr className="mapping-expression-row"><td colSpan={8}><ExpressionBuilder value={row.expression} columns={expressionColumns} onCancel={() => setEditingExpression(null)} onApply={(expression) => { updateRow(index, (current) => ({ target: current.target, expression })); setEditingExpression(null) }} /><p className="definition-help">{t('expressionRuntimeUnsupported')}</p></td></tr> : null}</Fragment>
              })}
            </tbody>
          </table>
        </div>
        <div className="mapping-pagination" aria-label={t('page', { page: currentPage + 1, pages })}>
          <button
            className="definition-button definition-button--quiet"
            type="button"
            disabled={currentPage === 0}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            {t('previous')}
          </button>
          <span>{t('page', { page: currentPage + 1, pages })}</span>
          <button
            className="definition-button definition-button--quiet"
            type="button"
            disabled={currentPage >= pages - 1}
            onClick={() => setPage((current) => Math.min(pages - 1, current + 1))}
          >
            {t('next')}
          </button>
        </div>
      </section>

      <section className="mapping-section mapping-strategy" aria-labelledby="strategy-title">
        <h3 id="strategy-title">{t('writeStrategy')}</h3>
        <label>
          <span>{t('writeStrategy')}</span>
          <select
            value={value.writeStrategy.kind}
            onChange={(event) => {
              const kind = event.target.value as MappingContent['writeStrategy']['kind']
              onChange({
                ...value,
                writeStrategy: kind === 'MERGE' ? { kind, key: value.writeStrategy.key ?? [] } : { kind },
              })
            }}
          >
            <option value="APPEND">APPEND</option>
            <option value="STAGED_REPLACE">STAGED_REPLACE</option>
            <option value="MERGE">MERGE</option>
            <option value="TRUNCATE_LOAD">TRUNCATE_LOAD</option>
            <option value="ATOMIC_DELETE_INSERT">ATOMIC_DELETE_INSERT</option>
          </select>
        </label>
        {value.writeStrategy.kind === 'MERGE' && (
          <label>
            <span>{t('mergeKeys')}</span>
            <input
              placeholder={t('mergeKeysHint')}
              value={(value.writeStrategy.key ?? []).join(', ')}
              onChange={(event) =>
                onChange({
                  ...value,
                  writeStrategy: {
                    kind: 'MERGE',
                    key: event.target.value.split(',').map((key) => key.trim()).filter(Boolean),
                  },
                })
              }
            />
          </label>
        )}
      </section>
    </div>
  )
}
