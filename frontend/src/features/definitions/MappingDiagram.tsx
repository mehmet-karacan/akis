import { Background, Controls, Handle, Position, ReactFlow, type NodeProps, type Node, type Connection } from '@xyflow/react'
import { useMemo } from 'react'
import { useDefinitionsI18n } from './i18n'
import type { MappingContent } from './types'

type DatasetNode = Node<{ name: string; role: string; columns: string[]; empty: string }, 'dataset'>
function DatasetCard({ data }: NodeProps<DatasetNode>) {
  return <div className="mapping-diagram-card"><header><strong>{data.name}</strong></header>{data.columns.length ? data.columns.map((column) => <div className="mapping-diagram-column" key={column}>{data.role === 'TARGET' && <Handle type="target" position={Position.Left} id={column} />}<span>{column}</span>{data.role === 'SOURCE' && <Handle type="source" position={Position.Right} id={column} />}</div>) : <p>{data.empty}</p>}</div>
}
const nodeTypes = { dataset: DatasetCard }
export function canConnectMapping(value: MappingContent, connection: Connection): boolean {
  return !!connection.sourceHandle && !!connection.targetHandle
    && value.datasets.some((dataset) => dataset.id === connection.source && dataset.role === 'SOURCE')
    && value.datasets.some((dataset) => dataset.id === connection.target && dataset.role === 'TARGET')
    && !value.columnMappings.some((row) => row.target.dataset === connection.target && row.target.column === connection.targetHandle)
}

export function MappingDiagram({ value, columns, onChange }: { value: MappingContent; columns: Record<string, string[]>; onChange(value: MappingContent): void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const nodes = useMemo(() => {
    const offsets = { SOURCE: 0, TARGET: 0 }
    return value.datasets.map((dataset): DatasetNode => {
      const names = columns[dataset.id] ?? []
      const y = offsets[dataset.role]
      offsets[dataset.role] += Math.max(100, names.length * 28 + 50) + 40
      return { id: dataset.id, type: 'dataset', position: { x: dataset.role === 'SOURCE' ? 0 : 520, y }, data: { name: dataset.name || dataset.id, role: dataset.role, columns: names, empty: tr ? 'Modelden Bir Veri Nesnesi Seçin' : 'Select a Data Object From a Model' } }
    })
  }, [value.datasets, columns, tr])
  const edges = value.columnMappings.flatMap((row, index) => row.source?.column && row.target.column && columns[row.source.dataset]?.includes(row.source.column) && columns[row.target.dataset]?.includes(row.target.column) ? [{ id: String(index), source: row.source.dataset, target: row.target.dataset, sourceHandle: row.source.column, targetHandle: row.target.column }] : [])
  return <section aria-label={tr ? 'Kolon Bağlantıları Diyagramı' : 'Column Connections Diagram'}><p className="definition-help">{tr ? 'Kaynak kolonun ucunu boş bir hedef kolona sürükleyin. İfadeleri ve mevcut eşleşmeleri aşağıdaki tabloda düzenleyebilirsiniz.' : 'Drag a source column port to an unmapped target column. Edit expressions and existing mappings in the table below.'}</p><div className="mapping-diagram"><ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} nodesDraggable={false} fitView fitViewOptions={{ maxZoom: 1 }} minZoom={0.15} isValidConnection={(connection) => canConnectMapping(value, connection as Connection)} onConnect={(connection) => { if (canConnectMapping(value, connection)) onChange({ ...value, columnMappings: [...value.columnMappings, { source: { dataset: connection.source, column: connection.sourceHandle! }, target: { dataset: connection.target, column: connection.targetHandle! } }] }) }}><Background /><Controls showInteractive={false} /></ReactFlow></div></section>
}
