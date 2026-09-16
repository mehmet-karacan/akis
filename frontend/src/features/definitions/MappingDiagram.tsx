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
import { useEffect, useMemo, useState, type DragEvent } from 'react'
import { useDefinitionsI18n } from './i18n'
import type { MappingContent } from './types'
import type { SchemaSnapshotColumn } from '../topology/api'
import { decodeModelObjectDrag, MODEL_OBJECT_DRAG_TYPE } from '../models/modelObjectDrag'

type DatasetNodeData = {
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
}

type DatasetNode = Node<DatasetNodeData, 'dataset'>

function DatasetCard({ data }: NodeProps<DatasetNode>) {
  const dropObject = (event: DragEvent<HTMLDivElement>) => {
    const payload = decodeModelObjectDrag(event.dataTransfer.getData(MODEL_OBJECT_DRAG_TYPE))
    if (!payload || !data.onDropObject) return
    event.preventDefault()
    event.stopPropagation()
    data.onDropObject(payload.objectUuid)
  }
  return (
    <div className={`mapping-diagram-card mapping-diagram-card--${data.role.toLowerCase()}`} onDragOver={event => {
      if (event.dataTransfer.types.includes(MODEL_OBJECT_DRAG_TYPE)) {
        event.preventDefault()
        event.dataTransfer.dropEffect = 'copy'
      }
    }} onDrop={dropObject}>
      <header>
        <strong>{data.name}</strong>
        <span>{data.roleLabel}</span>
      </header>
      {data.columns.length ? data.columns.map((column) => {
        const connected = data.connectedColumns.includes(column.reference)
        const portLabel = data.role === 'SOURCE' ? `${column.reference}: ${data.dragLabel}` : `${column.reference}: ${data.dropLabel}`
        const size = column.length != null ? `L:${column.length}` : column.precision != null ? `P:${column.precision}${column.scale != null ? `,${column.scale}` : ''}` : column.timePrecision != null ? `P:${column.timePrecision}` : '—'
        return (
          <div className={`mapping-diagram-column${connected ? ' is-connected' : ''}`} key={column.reference} title={`${column.reference} · ${column.producerType}`}>
            {data.role === 'TARGET' && (
              <Handle
                aria-label={portLabel}
                className="mapping-diagram-port mapping-diagram-port--target"
                id={column.reference}
                isConnectable={!connected}
                position={Position.Left}
                title={portLabel}
                type="target"
              />
            )}
            <small className="mapping-column-type">{column.canonicalType || column.producerType}</small>
            <span className="mapping-column-name" title={column.reference}>{column.reference}</span>
            <small className="mapping-column-size">{size}</small>
            {connected && <small aria-label={data.role === 'TARGET' ? data.dropLabel : data.dragLabel}>✓</small>}
            {data.role === 'SOURCE' && (
              <Handle
                aria-label={portLabel}
                className="mapping-diagram-port mapping-diagram-port--source"
                id={column.reference}
                position={Position.Right}
                title={portLabel}
                type="source"
              />
            )}
          </div>
        )
      }) : <p>{data.empty}<small>{data.objectDropLabel}</small></p>}
    </div>
  )
}

const nodeTypes = { dataset: DatasetCard }

export function canConnectMapping(
  value: MappingContent,
  connection: Connection,
  columns?: Record<string, ReadonlyArray<string | SchemaSnapshotColumn>>,
): boolean {
  const sourceHandle = connection.sourceHandle
  const targetHandle = connection.targetHandle
  return !!sourceHandle && !!targetHandle
    && value.datasets.some((dataset) => dataset.id === connection.source && dataset.role === 'SOURCE')
    && value.datasets.some((dataset) => dataset.id === connection.target && dataset.role === 'TARGET')
    && (!columns || columns[connection.source]?.some(column => typeof column === 'string' ? column === sourceHandle : column.reference === sourceHandle) === true)
    && (!columns || columns[connection.target]?.some(column => typeof column === 'string' ? column === targetHandle : column.reference === targetHandle) === true)
    && !value.columnMappings.some((row) => row.target.dataset === connection.target && row.target.column === targetHandle)
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
      ...value.columnMappings,
      {
        source: { dataset: connection.source, column: connection.sourceHandle! },
        target: { dataset: connection.target, column: connection.targetHandle! },
      },
    ],
  }
}

export function MappingDiagram({ value, columns, onChange, onDropObject }: { value: MappingContent; columns: Record<string, SchemaSnapshotColumn[]>; onChange(value: MappingContent): void; onDropObject?: (datasetId: string, objectUuid: string) => void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const [instance, setInstance] = useState<ReactFlowInstance | null>(null)
  const fitKey = value.datasets.map(dataset => `${dataset.id}:${dataset.dataObjectUuid ?? ''}:${columns[dataset.id]?.length ?? 0}`).join('|')
  const nodes = useMemo(() => {
    const offsets = { SOURCE: 0, TARGET: 0 }
    return value.datasets.map((dataset): DatasetNode => {
      const datasetColumns = columns[dataset.id] ?? []
      const y = offsets[dataset.role]
      offsets[dataset.role] += Math.max(110, datasetColumns.length * 36 + 54) + 40
      const connectedColumns = value.columnMappings.flatMap((mapping) => {
        if (dataset.role === 'SOURCE' && mapping.source?.dataset === dataset.id && mapping.source.column) return [mapping.source.column]
        if (dataset.role === 'TARGET' && mapping.target.dataset === dataset.id && mapping.target.column) return [mapping.target.column]
        return []
      })
      return {
        id: dataset.id,
        type: 'dataset',
        position: { x: dataset.role === 'SOURCE' ? 0 : 620, y },
        data: {
          name: dataset.name || dataset.id,
          role: dataset.role,
          roleLabel: dataset.role === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target'),
          columns: datasetColumns,
          connectedColumns,
          empty: tr ? 'Modelden Bir Veri Nesnesi Seçin' : 'Select a Data Object From a Model',
          dragLabel: tr ? 'eşleştirmek için sürükleyin' : 'drag to map',
          dropLabel: tr ? 'buraya bırakın' : 'drop here',
          objectDropLabel: tr ? 'Sol taraftaki model nesnesini buraya bırakabilirsiniz.' : 'You can drop a model object from the left sidebar here.',
          onDropObject: onDropObject ? (objectUuid: string) => onDropObject(dataset.id, objectUuid) : undefined,
        },
      }
    })
  }, [value.datasets, value.columnMappings, columns, tr, onDropObject])

  const edges: Edge[] = value.columnMappings.flatMap((row, index) => {
    const source = row.source
    return source?.column
      && row.target.column
      && columns[source.dataset]?.some(column => column.reference === source.column)
      && columns[row.target.dataset]?.some(column => column.reference === row.target.column)
      ? [{
          id: `mapping-${index}`,
          source: source.dataset,
          target: row.target.dataset,
          sourceHandle: source.column,
          targetHandle: row.target.column,
          data: { mappingIndex: index },
        }]
      : []
  })

  const removeEdges = (deletedEdges: Edge[]) => {
    const indexes = new Set(deletedEdges.flatMap((edge) => (
      typeof edge.data?.mappingIndex === 'number' ? [edge.data.mappingIndex] : []
    )))
    if (indexes.size > 0) onChange({ ...value, columnMappings: value.columnMappings.filter((_, index) => !indexes.has(index)) })
  }

  useEffect(() => {
    if (!instance) return
    let secondFrame = 0
    const firstFrame = requestAnimationFrame(() => {
      secondFrame = requestAnimationFrame(() => void instance.fitView({ maxZoom: 1, padding: .12, duration: 180 }))
    })
    return () => { cancelAnimationFrame(firstFrame); if (secondFrame) cancelAnimationFrame(secondFrame) }
  }, [fitKey, instance])

  return (
    <section aria-label={tr ? 'Kolon Eşleştirme Diyagramı' : 'Column Mapping Diagram'}>
      <div className="mapping-diagram-guide">
        <strong>{tr ? 'Sürükle ve bırak ile eşleştirin' : 'Map with drag and drop'}</strong>
        <span>{tr ? 'Sol taraftaki Modeller ağacından tabloyu Kaynak veya Hedef kartına bırakın; ardından kaynak kolon bağlantısını hedef kolona sürükleyin. Bir bağlantıyı seçip Delete tuşuyla kaldırabilirsiniz.' : 'Drop a table from the Models tree into a Source or Target card, then drag a source column connector to its target column. Select a connection and press Delete to remove it.'}</span>
      </div>
      <div className="mapping-diagram">
        <ReactFlow
          connectionRadius={24}
          defaultEdgeOptions={{ type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed }, className: 'mapping-diagram-edge' }}
          edges={edges}
          fitView
          fitViewOptions={{ maxZoom: 1 }}
          isValidConnection={(connection) => canConnectMapping(value, connection as Connection, columns)}
          minZoom={0.15}
          nodes={nodes}
          nodesDraggable={false}
          nodeTypes={nodeTypes}
          onInit={setInstance}
          onConnect={(connection) => {
            const next = connectMapping(value, connection, columns)
            if (next !== value) onChange(next)
          }}
          onEdgesDelete={removeEdges}
        >
          <Background />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
    </section>
  )
}
