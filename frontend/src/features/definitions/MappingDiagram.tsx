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
} from '@xyflow/react'
import { useMemo } from 'react'
import { useDefinitionsI18n } from './i18n'
import type { MappingContent } from './types'

type DatasetNodeData = {
  name: string
  role: string
  roleLabel: string
  columns: string[]
  connectedColumns: string[]
  empty: string
  dragLabel: string
  dropLabel: string
}

type DatasetNode = Node<DatasetNodeData, 'dataset'>

function DatasetCard({ data }: NodeProps<DatasetNode>) {
  return (
    <div className={`mapping-diagram-card mapping-diagram-card--${data.role.toLowerCase()}`}>
      <header>
        <strong>{data.name}</strong>
        <span>{data.roleLabel}</span>
      </header>
      {data.columns.length ? data.columns.map((column) => {
        const connected = data.connectedColumns.includes(column)
        const portLabel = data.role === 'SOURCE' ? `${column}: ${data.dragLabel}` : `${column}: ${data.dropLabel}`
        return (
          <div className={`mapping-diagram-column${connected ? ' is-connected' : ''}`} key={column}>
            {data.role === 'TARGET' && (
              <Handle
                aria-label={portLabel}
                className="mapping-diagram-port mapping-diagram-port--target"
                id={column}
                isConnectable={!connected}
                position={Position.Left}
                title={portLabel}
                type="target"
              />
            )}
            <span title={column}>{column}</span>
            {connected && <small aria-label={data.role === 'TARGET' ? data.dropLabel : data.dragLabel}>✓</small>}
            {data.role === 'SOURCE' && (
              <Handle
                aria-label={portLabel}
                className="mapping-diagram-port mapping-diagram-port--source"
                id={column}
                position={Position.Right}
                title={portLabel}
                type="source"
              />
            )}
          </div>
        )
      }) : <p>{data.empty}</p>}
    </div>
  )
}

const nodeTypes = { dataset: DatasetCard }

export function canConnectMapping(
  value: MappingContent,
  connection: Connection,
  columns?: Record<string, string[]>,
): boolean {
  const sourceHandle = connection.sourceHandle
  const targetHandle = connection.targetHandle
  return !!sourceHandle && !!targetHandle
    && value.datasets.some((dataset) => dataset.id === connection.source && dataset.role === 'SOURCE')
    && value.datasets.some((dataset) => dataset.id === connection.target && dataset.role === 'TARGET')
    && (!columns || columns[connection.source]?.includes(sourceHandle) === true)
    && (!columns || columns[connection.target]?.includes(targetHandle) === true)
    && !value.columnMappings.some((row) => row.target.dataset === connection.target && row.target.column === targetHandle)
}

export function connectMapping(
  value: MappingContent,
  connection: Connection,
  columns?: Record<string, string[]>,
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

export function MappingDiagram({ value, columns, onChange }: { value: MappingContent; columns: Record<string, string[]>; onChange(value: MappingContent): void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const nodes = useMemo(() => {
    const offsets = { SOURCE: 0, TARGET: 0 }
    return value.datasets.map((dataset): DatasetNode => {
      const names = columns[dataset.id] ?? []
      const y = offsets[dataset.role]
      offsets[dataset.role] += Math.max(100, names.length * 32 + 54) + 40
      const connectedColumns = value.columnMappings.flatMap((mapping) => {
        if (dataset.role === 'SOURCE' && mapping.source?.dataset === dataset.id && mapping.source.column) return [mapping.source.column]
        if (dataset.role === 'TARGET' && mapping.target.dataset === dataset.id && mapping.target.column) return [mapping.target.column]
        return []
      })
      return {
        id: dataset.id,
        type: 'dataset',
        position: { x: dataset.role === 'SOURCE' ? 0 : 520, y },
        data: {
          name: dataset.name || dataset.id,
          role: dataset.role,
          roleLabel: dataset.role === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target'),
          columns: names,
          connectedColumns,
          empty: tr ? 'Modelden Bir Veri Nesnesi Seçin' : 'Select a Data Object From a Model',
          dragLabel: tr ? 'eşleştirmek için sürükleyin' : 'drag to map',
          dropLabel: tr ? 'buraya bırakın' : 'drop here',
        },
      }
    })
  }, [value.datasets, value.columnMappings, columns, tr])

  const edges: Edge[] = value.columnMappings.flatMap((row, index) => (
    row.source?.column
      && row.target.column
      && columns[row.source.dataset]?.includes(row.source.column)
      && columns[row.target.dataset]?.includes(row.target.column)
      ? [{
          id: `mapping-${index}`,
          source: row.source.dataset,
          target: row.target.dataset,
          sourceHandle: row.source.column,
          targetHandle: row.target.column,
          data: { mappingIndex: index },
        }]
      : []
  ))

  const removeEdges = (deletedEdges: Edge[]) => {
    const indexes = new Set(deletedEdges.flatMap((edge) => (
      typeof edge.data?.mappingIndex === 'number' ? [edge.data.mappingIndex] : []
    )))
    if (indexes.size > 0) onChange({ ...value, columnMappings: value.columnMappings.filter((_, index) => !indexes.has(index)) })
  }

  return (
    <section aria-label={tr ? 'Kolon Eşleştirme Diyagramı' : 'Column Mapping Diagram'}>
      <div className="mapping-diagram-guide">
        <strong>{tr ? 'Sürükle ve bırak ile eşleştirin' : 'Map with drag and drop'}</strong>
        <span>{tr ? 'Kaynak kolonun sağındaki bağlantı noktasını hedef kolonun solundaki boş noktaya bırakın. Bir bağlantıyı seçip Delete tuşuyla kaldırabilirsiniz.' : 'Drag the port on the right of a source column to an empty port on the left of a target column. Select a connection and press Delete to remove it.'}</span>
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
