import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react'
import { useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent } from 'react'
import { useDefinitionsI18n } from './i18n'
import type { MappingContent } from './types'
import type { SchemaSnapshotColumn } from '../topology/api'
import { decodeModelObjectDrag, MODEL_OBJECT_DRAG_TYPE } from '../models/modelObjectDrag'
import { DatabaseTypeIcon } from './DatabaseTypeIcon'
import { expressionColumnReferences } from './mappingReferences'
import { useResolvedTheme } from '../../core/theme/ThemeContext'
import { Drawer } from 'antd'
import { Check, Database, DatabaseZap, Filter, GitMerge, Minus } from 'lucide-react'
import { Button } from '../../core/ui'
import { MappingFilterEditor } from './MappingFilters'
import { mappingFilterSql, nextFilterId } from './mappingFilterSql'
import { FILTER_COMPONENT_DRAG_TYPE, mappingFlow } from './mappingFlow'
import type { MappingFilter } from './types'
import { useColumnPointerDrag, type ColumnDropTarget } from './useColumnPointerDrag'
import { columnSize } from '../topology/columnPresentation'
import { typesCompatible } from './mappingColumnCompatibility'
export { typesCompatible } from './mappingColumnCompatibility'

type DatasetNodeData = {
  datasetId: string
  name: string
  role: string
  roleLabel: string
  columns: SchemaSnapshotColumn[]
  connectedColumns: string[]
  empty: string
  dragLabel: string
  dropLabel: string
  objectDropLabel: string
  onDropObject?: (objectUuid: string) => void
  onSelectColumn?: (selection: MappingColumnSelection) => void
  onSelectObject?: () => void
  propertiesLabel: string
  mappedLabel: string
  unmappedLabel: string
  selectedColumn?: string
  onMapColumns?: (sourceObject: string, sourceColumn: string, targetObject: string, targetColumn: string) => void
  keyboardSource?: { object: string; column: string } | null
  onKeyboardSource?: (source: { object: string; column: string } | null) => void
  dragSource?: { canonicalType?: string } | null
  onDragSource?: (source: { canonicalType?: string } | null) => void
  dropTarget?: ColumnDropTarget | null
  onDropTarget?: (target: ColumnDropTarget | null) => void
  onAddFilter?: (column?: string) => void
}

export interface MappingColumnSelection {
  datasetId: string
  role: 'SOURCE' | 'TARGET'
  column: SchemaSnapshotColumn
}

type DatasetNode = Node<DatasetNodeData, 'dataset'>

function DatasetCard({ data }: NodeProps<DatasetNode>) {
  const [incompatibleColumn, setIncompatibleColumn] = useState<string | null>(null)
  const pointer = useColumnPointerDrag({
    enabled: data.role === 'SOURCE',
    onStart: canonicalType => data.onDragSource?.({ canonicalType }),
    onHover: target => data.onDropTarget?.(target),
    onEnd: () => { data.onDragSource?.(null); data.onDropTarget?.(null) },
    onDrop: (column, target) => data.onMapColumns?.(data.datasetId, column, target.object, target.column),
  })
  const dropObject = (event: DragEvent<HTMLDivElement>) => {
    const payload = decodeModelObjectDrag(event.dataTransfer.getData(MODEL_OBJECT_DRAG_TYPE))
    if (!payload || !data.onDropObject) return
    event.preventDefault()
    event.stopPropagation()
    data.onDropObject(payload.objectUuid)
  }
  const selectColumn = (column: SchemaSnapshotColumn) => data.onSelectColumn?.({
    datasetId: data.datasetId,
    role: data.role as 'SOURCE' | 'TARGET',
    column,
  })
  return (
    <div className={`mapping-diagram-card mapping-diagram-card--${data.role.toLowerCase()}`} onDragOver={event => {
      if (data.onAddFilter && event.dataTransfer.types.includes(FILTER_COMPONENT_DRAG_TYPE)) {
        event.preventDefault(); event.stopPropagation(); event.dataTransfer.dropEffect = 'copy'
      }
      if (event.dataTransfer.types.includes(MODEL_OBJECT_DRAG_TYPE)) {
        event.preventDefault()
        event.dataTransfer.dropEffect = 'copy'
      }
    }} onDrop={event => {
      if (data.onAddFilter && event.dataTransfer.types.includes(FILTER_COMPONENT_DRAG_TYPE)) {
        event.preventDefault(); event.stopPropagation(); data.onAddFilter(); return
      }
      dropObject(event)
    }}>
      <Handle type={data.role === 'SOURCE' ? 'source' : 'target'} position={data.role === 'SOURCE' ? Position.Right : Position.Left} isConnectable={false} className="mapping-flow-handle" />
      <header>
        <button type="button" className="mapping-node-title nodrag nopan" title={data.name} aria-label={`${data.propertiesLabel}: ${data.name}`} onClick={data.onSelectObject}><span className={`mapping-node-mark mapping-node-mark--${data.role.toLowerCase()}`} aria-hidden="true">{data.role === 'SOURCE' ? <DatabaseZap size={13} /> : <Database size={13} />}</span><strong>{data.name}</strong></button>
        <span>{data.roleLabel} · {data.columns.length}</span>
      </header>
      {data.columns.length ? data.columns.map((column) => {
        const connected = data.connectedColumns.includes(column.reference)
        const size = columnSize(column)
        const columnDragType = 'application/x-akis-mapping-column'
        const keyboardSelected = data.role === 'SOURCE' && data.keyboardSource?.object === data.datasetId && data.keyboardSource.column === column.reference
        const pointerOver = data.dropTarget?.object === data.datasetId && data.dropTarget.column === column.reference
        const pointerCompatible = typesCompatible(data.dragSource?.canonicalType, column.canonicalType)
        const handleKeyboardMap = (event: KeyboardEvent<HTMLDivElement>) => {
          if (event.key !== 'Enter' && event.key !== ' ') return
          event.preventDefault()
          if (data.role === 'SOURCE') data.onKeyboardSource?.(keyboardSelected ? null : { object: data.datasetId, column: column.reference })
          else if (data.keyboardSource) {
            data.onMapColumns?.(data.keyboardSource.object, data.keyboardSource.column, data.datasetId, column.reference)
            data.onKeyboardSource?.(null)
          } else selectColumn(column)
        }
        return (
          <div
            className={`mapping-diagram-column nodrag nopan${connected ? ' is-connected' : ''}${data.selectedColumn === column.reference ? ' is-selected' : ''}${keyboardSelected ? ' is-keyboard-source' : ''}${incompatibleColumn === column.reference || (pointerOver && !pointerCompatible) ? ' is-incompatible-target' : ''}${pointerOver && pointerCompatible ? ' is-drop-target' : ''}`}
            draggable={false}
            data-mapping-target={data.role === 'TARGET' ? 'true' : undefined}
            data-mapping-object={data.datasetId}
            data-mapping-column={column.reference}
            data-mapping-type={column.canonicalType}
            key={column.reference}
            onClick={() => { if (!pointer.consumeClick()) selectColumn(column) }}
            onPointerDown={event => pointer.onPointerDown(event, column.reference, column.canonicalType)}
            onPointerMove={pointer.onPointerMove}
            onPointerUp={pointer.onPointerUp}
            onPointerCancel={pointer.onPointerCancel}
            onLostPointerCapture={pointer.onLostPointerCapture}
            onDragStart={event => {
              if (data.role !== 'SOURCE') return
              event.stopPropagation()
              data.onDragSource?.({ canonicalType: column.canonicalType })
              event.dataTransfer.effectAllowed = 'link'
              event.dataTransfer.setData(columnDragType, JSON.stringify({ object: data.datasetId, column: column.reference, canonicalType: column.canonicalType }))
            }}
            onDragEnd={() => data.onDragSource?.(null)}
            onDragOver={event => {
              if (data.onAddFilter && event.dataTransfer.types.includes(FILTER_COMPONENT_DRAG_TYPE)) {
                event.preventDefault(); event.stopPropagation(); event.dataTransfer.dropEffect = 'copy'; return
              }
              if (data.role === 'TARGET' && event.dataTransfer.types.includes(columnDragType)) {
                event.preventDefault(); event.stopPropagation()
                const compatible = typesCompatible(data.dragSource?.canonicalType, column.canonicalType)
                setIncompatibleColumn(compatible ? null : column.reference)
                event.dataTransfer.dropEffect = compatible ? 'link' : 'none'
              }
            }}
            onDragLeave={() => setIncompatibleColumn(current => current === column.reference ? null : current)}
            onDrop={event => {
              if (data.onAddFilter && event.dataTransfer.types.includes(FILTER_COMPONENT_DRAG_TYPE)) {
                event.preventDefault(); event.stopPropagation(); data.onAddFilter(column.reference); return
              }
              if (data.role !== 'TARGET') return
              try {
                const source = JSON.parse(event.dataTransfer.getData(columnDragType)) as { object: string; column: string }
                if (!source.object || !source.column || !typesCompatible((source as { canonicalType?: string }).canonicalType,column.canonicalType)) return
                event.preventDefault(); event.stopPropagation()
                setIncompatibleColumn(null)
                data.onMapColumns?.(source.object, source.column, data.datasetId, column.reference)
              } catch { /* Invalid external drag payload is ignored. */ }
            }}
            onKeyDown={handleKeyboardMap}
            role="button"
            tabIndex={0}
            title={`${column.reference} · ${column.producerType}`}
          >
            <small className="mapping-column-type" title={column.producerType}><DatabaseTypeIcon type={column.producerType} /><abbr title={column.producerType}>{column.producerType}</abbr></small>
            <span className="mapping-column-name" title={column.reference}>{column.reference}</span>
            <small className="mapping-column-size">{size}</small>
            <small className={`mapping-column-status${connected ? ' is-mapped' : ' is-unmapped'}`} aria-label={connected ? data.mappedLabel : data.unmappedLabel} title={connected ? data.mappedLabel : data.unmappedLabel}>{connected ? <Check size={12} aria-hidden="true" /> : <Minus size={12} aria-hidden="true" />}</small>
          </div>
        )
      }) : <p>{data.empty}<small>{data.objectDropLabel}</small></p>}
    </div>
  )
}

type OperationNode = Node<{ label: string; description: string; kind: 'filter' | 'join'; onEdit(): void; incomplete?: boolean }, 'operation'>
function OperationCard({ data }: NodeProps<OperationNode>) {
  return <div className={`mapping-operation-node${data.incomplete ? ' is-incomplete' : ''}`}>
    <Handle type="target" position={Position.Left} isConnectable={false} />
    <button type="button" className="nodrag nopan" onClick={data.onEdit} aria-label={data.label}>
      <strong>{data.kind === 'filter' ? <Filter size={18} /> : <GitMerge size={18} />}{data.label}</strong>
      <span>{data.description}</span>
    </button>
    <Handle type="source" position={Position.Right} isConnectable={false} />
  </div>
}
const nodeTypes = { dataset: DatasetCard, operation: OperationCard }

export function canConnectMapping(
  value: MappingContent,
  connection: Connection,
  columns?: Record<string, ReadonlyArray<string | SchemaSnapshotColumn>>,
): boolean {
  const sourceHandle = connection.sourceHandle
  const targetHandle = connection.targetHandle
  const sourceColumn = columns?.[connection.source]?.find(column => typeof column !== 'string' && column.reference === sourceHandle)
  const targetColumn = columns?.[connection.target]?.find(column => typeof column !== 'string' && column.reference === targetHandle)
  return !!sourceHandle && !!targetHandle
    && value.sources.some((source) => source.id === connection.source)
    && value.target.id === connection.target
    && (!columns || columns[connection.source]?.some(column => typeof column === 'string' ? column === sourceHandle : column.reference === sourceHandle) === true)
    && (!columns || columns[connection.target]?.some(column => typeof column === 'string' ? column === targetHandle : column.reference === targetHandle) === true)
    && typesCompatible(typeof sourceColumn === 'string' ? undefined : sourceColumn?.canonicalType, typeof targetColumn === 'string' ? undefined : targetColumn?.canonicalType)
}

export function connectMapping(
  value: MappingContent,
  connection: Connection,
  columns?: Record<string, ReadonlyArray<string | SchemaSnapshotColumn>>,
): MappingContent {
  if (!canConnectMapping(value, connection, columns)) return value
  return {
    ...value,
    columnMappings: [
      ...value.columnMappings.filter(row => !(row.target.object === connection.target && row.target.column === connection.targetHandle)),
      { source: { object: connection.source, column: connection.sourceHandle! }, target: { object: connection.target, column: connection.targetHandle! } },
    ],
  }
}

export function MappingDiagram({ projectUuid, value, columns, onChange, onDropObject, onSelectColumn, onSelectObject, selectedColumn, onEditJoins }: { projectUuid: string; value: MappingContent; columns: Record<string, SchemaSnapshotColumn[]>; onChange(value: MappingContent): void; onDropObject?: (datasetId: string, objectUuid: string) => void; onSelectColumn?: (selection: MappingColumnSelection) => void; onSelectObject?: (objectId: string) => void; selectedColumn?: { datasetId: string; columnReference: string } | null; onEditJoins?(): void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const colorMode = useResolvedTheme()
  const [instance, setInstance] = useState<ReactFlowInstance | null>(null)
  const [nodeSizes, setNodeSizes] = useState<Record<string, { width: number; height: number }>>({})
  const viewportRef = useRef<HTMLDivElement>(null)
  const columnDragActive = useRef(false)
  const [keyboardSource, setKeyboardSource] = useState<{ object: string; column: string } | null>(null)
  const [dragSource, setDragSource] = useState<{ canonicalType?: string } | null>(null)
  const [dropTarget, setDropTarget] = useState<ColumnDropTarget | null>(null)
  const [editingFilter, setEditingFilter] = useState<MappingFilter | null>(null)
  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const addFilter = (object: string, column?: string) => setEditingFilter({ id: nextFilterId(value.filters), scope: 'SOURCE', object,
    ...(column ? { predicate: { kind: 'UNARY', operator: 'IS NOT NULL', argument: { kind: 'COLUMN', dataset: object, column } } } : {}) })
  const objects = [...value.sources.map(source => ({ ...source, role: 'SOURCE' as const })), { ...value.target, role: 'TARGET' as const }]
  const flow = mappingFlow(value, Object.fromEntries(objects.map(object => [object.id, (columns[object.id]?.length ?? 0) * 36 + 54])))
  const fitKey = objects.map(object => `${object.id}:${object.dataObjectUuid ?? ''}:${columns[object.id]?.length ?? 0}`).join('|') + JSON.stringify(value.filters.map(filter => [filter.id, filter.scope, filter.object]))
  const nodes = useMemo(() => {
    return objects.map((dataset): DatasetNode => {
      const datasetColumns = columns[dataset.id] ?? []
      const connectedColumns = value.columnMappings.flatMap((mapping) => {
        if (dataset.role === 'SOURCE' && mapping.source?.object === dataset.id && mapping.source.column) return [mapping.source.column]
        if (dataset.role === 'SOURCE' && mapping.expression) return expressionColumnReferences(mapping.expression).filter(reference => reference.object === dataset.id).map(reference => reference.column)
        if (dataset.role === 'TARGET' && mapping.target.object === dataset.id && mapping.target.column) return [mapping.target.column]
        return []
      })
      return {
        id: dataset.id,
        type: 'dataset',
        measured: nodeSizes[dataset.id],
        position: flow.positions[dataset.id]!,
        data: {
          datasetId: dataset.id,
          name: dataset.alias || dataset.id,
          role: dataset.role,
          roleLabel: dataset.role === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target'),
          columns: datasetColumns,
          connectedColumns,
          empty: tr ? 'Modelden Bir Veri Nesnesi Seçin' : 'Select a Data Object From a Model',
          dragLabel: tr ? 'eşleştirmek için sürükleyin' : 'drag to map',
          dropLabel: tr ? 'buraya bırakın' : 'drop here',
          objectDropLabel: tr ? 'Sol taraftaki model nesnesini buraya bırakabilirsiniz.' : 'You can drop a model object from the left sidebar here.',
          onDropObject: onDropObject ? (objectUuid: string) => onDropObject(dataset.id, objectUuid) : undefined,
          onSelectColumn,
          onAddFilter: dataset.role === 'SOURCE' ? column => addFilter(dataset.id, column) : undefined,
          onSelectObject: onSelectObject ? () => { setSelectedNode(dataset.id); onSelectObject(dataset.id) } : undefined,
          propertiesLabel: tr ? 'Nesne Özellikleri' : 'Object Properties',
          mappedLabel: tr ? 'Eşlendi' : 'Mapped',
          unmappedLabel: tr ? 'Eşlenmedi' : 'Not mapped',
          selectedColumn: selectedColumn?.datasetId === dataset.id ? selectedColumn.columnReference : undefined,
          keyboardSource,
          dragSource,
          dropTarget,
          onDropTarget: setDropTarget,
          onDragSource: source => { columnDragActive.current = Boolean(source); setDragSource(source) },
          onKeyboardSource: setKeyboardSource,
          onMapColumns: (sourceObject, sourceColumn, targetObject, targetColumn) => {
            const next = connectMapping(value, { source: sourceObject, sourceHandle: sourceColumn, target: targetObject, targetHandle: targetColumn } as Connection, columns)
            if (next !== value) onChange(next)
          },
        },
      }
    })
  }, [value, columns, tr, onDropObject, onSelectColumn, onSelectObject, selectedColumn, selectedNode, keyboardSource, dragSource, dropTarget, nodeSizes])

  const edges: Edge[] = flow.edges.map(edge => ({ ...edge, deletable: false, selectable: false }))
  const operationNodes: OperationNode[] = flow.operations.map(operation => {
    const filter = value.filters.find(filter => filter.id === operation.filterId)
    let description = value.joins.map(join => `${join.type}: ${value.sources.find(source => source.id === join.left.object)?.alias}.${join.left.column} = ${value.sources.find(source => source.id === join.right.object)?.alias}.${join.right.column}`).join('\n')
    if (filter) { try { description = mappingFilterSql(filter, value) } catch { description = tr ? 'Filtreyi yeniden tanımlayın.' : 'Redefine this filter.' } }
    if (operation.incomplete) description = tr ? 'Kaynaklar arasındaki join koşullarını tamamlayın.' : 'Complete join conditions between the sources.'
    return { id: operation.id, type: 'operation', measured: nodeSizes[operation.id], position: operation.position, data: { kind: operation.kind, incomplete: operation.incomplete,
      label: filter ? `${tr ? 'Filtre' : 'Filter'} · ${filter.scope === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Genel' : 'Global')}` : 'Join', description,
      onEdit: () => { if (filter) setEditingFilter(filter); else onEditJoins?.() } } }
  })

  useEffect(() => {
    if (!instance) return
    let firstFrame = 0
    let secondFrame = 0
    const fit = () => {
      cancelAnimationFrame(firstFrame); cancelAnimationFrame(secondFrame)
      if (columnDragActive.current || !viewportRef.current?.clientHeight) return
      firstFrame = requestAnimationFrame(() => {
        secondFrame = requestAnimationFrame(() => {
          if (!columnDragActive.current) void instance.fitView({ maxZoom: 1, padding: .12, duration: 0 })
        })
      })
    }
    // Opening/restoring the inspector changes the canvas size without changing
    // its nodes. Refit that viewport, never an in-progress column drag.
    const observer = new ResizeObserver(fit)
    if (viewportRef.current) observer.observe(viewportRef.current)
    fit()
    return () => { observer.disconnect(); cancelAnimationFrame(firstFrame); cancelAnimationFrame(secondFrame) }
  }, [fitKey, instance])

  return (
    <section aria-label={tr ? 'Kolon Eşleştirme Diyagramı' : 'Column Mapping Diagram'}>
      <div className="mapping-diagram-guide">
        <div className="mapping-component-palette" aria-label={tr ? 'Bileşenler' : 'Components'}>
          <span>{tr ? 'Bileşenler' : 'Components'}</span>
          <Button className="mapping-filter-palette-button" icon={<Filter size={16} />} draggable disabled={!value.sources.length}
            onDragStart={event => { event.dataTransfer.setData(FILTER_COMPONENT_DRAG_TYPE, 'FILTER'); event.dataTransfer.effectAllowed = 'copy' }}
            onClick={() => { const source = value.sources[0]; if (source) addFilter(source.id) }}>{tr ? 'Filtre' : 'Filter'}</Button>
        </div>
        <strong>{tr ? 'Sürükle ve bırak ile eşleştirin' : 'Map with drag and drop'}</strong>
        <span>{tr ? 'Modellerden tabloyu kaynak veya hedefe, kaynak kolonunu hedef kolona sürükleyin. Filtre bileşenini kaynak kolonuna bırakın. Oklar bileşen akışını, durum simgeleri kolon eşleşmelerini gösterir.' : 'Drop a model table onto a source or target; drag a source column onto a target column. Drop the Filter component onto a source column. Arrows show component flow; status icons show column mappings.'}</span>
      </div>
      <div className="mapping-diagram" ref={viewportRef}>
        <ReactFlow
          colorMode={colorMode}
          connectionRadius={24}
          defaultEdgeOptions={{ type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed }, className: 'mapping-diagram-edge' }}
          edges={edges}
          fitView
          fitViewOptions={{ maxZoom: 1 }}
          minZoom={0.15}
          nodes={[...nodes, ...operationNodes]}
          onPaneClick={() => setSelectedNode(null)}
          onNodesChange={changes => {
            // Controlled nodes must retain measured dimensions. Dropping them on
            // hover rerenders temporarily hides every node, losing the drop hit.
            setNodeSizes(previous => {
              let next = previous
              for (const change of changes) {
                if (change.type !== 'dimensions' || !change.dimensions) continue
                const size = previous[change.id]
                if (size?.width === change.dimensions.width && size?.height === change.dimensions.height) continue
                if (next === previous) next = { ...previous }
                next[change.id] = change.dimensions
              }
              return next
            })
          }}
          nodesConnectable={false}
          nodesDraggable={false}
          autoPanOnNodeDrag={false}
          autoPanOnConnect={false}
          autoPanOnNodeFocus={false}
          panOnDrag={[1, 2]}
          panOnScroll={false}
          zoomOnScroll={false}
          nodeTypes={nodeTypes}
          onInit={setInstance}
        >
          <Background />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
      <Drawer open={Boolean(editingFilter)} title={tr ? 'Filtre Özellikleri' : 'Filter Properties'} onClose={() => setEditingFilter(null)} size={540} destroyOnHidden>
        {editingFilter && <MappingFilterEditor key={editingFilter.id} projectUuid={projectUuid} value={value} columns={columns} filter={editingFilter} onCancel={() => setEditingFilter(null)} onApply={filter => {
          onChange({ ...value, filters: value.filters.some(item => item.id === filter.id) ? value.filters.map(item => item.id === filter.id ? filter : item) : [...value.filters, filter] })
          setEditingFilter(null)
        }} />}
        {editingFilter && value.filters.some(filter => filter.id === editingFilter.id) && <Button tone="danger" className="mapping-filter-remove" onClick={() => { onChange({ ...value, filters: value.filters.filter(filter => filter.id !== editingFilter.id) }); setEditingFilter(null) }}>{tr ? 'Filtreyi Kaldır' : 'Remove Filter'}</Button>}
      </Drawer>
    </section>
  )
}
