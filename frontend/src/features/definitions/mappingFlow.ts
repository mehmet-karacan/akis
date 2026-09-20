import type { MappingContent } from './types'

export const FILTER_COMPONENT_DRAG_TYPE = 'application/x-akis-filter-component'
export interface FlowOperation { id: string; kind: 'filter' | 'join'; filterId?: string; position: { x: number; y: number }; incomplete?: boolean }
/** Data-flow edges, not column mappings. SOURCE predicates precede the join;
 * GLOBAL predicates follow it, matching the staged query renderer. */
export function mappingFlow(value: MappingContent, heights: Record<string, number>) {
  const positions: Record<string, { x: number; y: number }> = {}
  const operations: FlowOperation[] = []
  const edges: { id: string; source: string; target: string }[] = []
  const tails: string[] = []
  let y = 0, nextX = 620
  const connect = (source: string, target: string) => edges.push({ id: `flow:${source}:${target}`, source, target })
  for (const source of value.sources) {
    positions[source.id] = { x: 0, y }
    let tail = source.id, x = 450
    for (const filter of value.filters.filter(filter => filter.scope === 'SOURCE' && filter.object === source.id)) {
      const id = `flow:filter:${filter.id}`
      operations.push({ id, kind: 'filter', filterId: filter.id, position: { x, y } })
      connect(tail, id); tail = id; x += 300
    }
    nextX = Math.max(nextX, x)
    tails.push(tail)
    y += Math.max(150, heights[source.id] ?? 150) + 48
  }
  let tail = tails[0]
  if (value.sources.length > 1) {
    const id = 'flow:join'
    const reached = new Set(value.sources.slice(0, 1).map(source => source.id))
    for (let round = 0; round < value.sources.length; round++) for (const join of value.joins) {
      if (reached.has(join.left.object) || reached.has(join.right.object)) { reached.add(join.left.object); reached.add(join.right.object) }
    }
    operations.push({ id, kind: 'join', position: { x: nextX, y: 0 }, incomplete: value.sources.some(source => !reached.has(source.id)) })
    tails.forEach(source => connect(source, id)); tail = id; nextX += 300
  }
  for (const filter of value.filters.filter(filter => filter.scope === 'GLOBAL')) {
    const id = `flow:filter:${filter.id}`
    operations.push({ id, kind: 'filter', filterId: filter.id, position: { x: nextX, y: 0 } })
    if (tail) connect(tail, id)
    tail = id; nextX += 300
  }
  positions[value.target.id] = { x: nextX, y: 0 }
  if (tail) connect(tail, value.target.id)
  return { positions, operations, edges }
}
