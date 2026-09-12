import type { RunStep } from './types'

export interface RunStepNode extends RunStep { children: RunStepNode[] }

const failedStatuses = new Set(['BASARISIZ', 'HATA_DEVAM', 'SONUC_BELIRSIZ'])

export function buildRunStepTree(steps: RunStep[]): RunStepNode[] {
  const nodes = new Map<string, RunStepNode>(steps.map((step) => [step.uuid, { ...step, children: [] }]))
  const roots: RunStepNode[] = []
  for (const step of steps) {
    const node = nodes.get(step.uuid)!
    const parent = step.parentUuid ? nodes.get(step.parentUuid) : undefined
    if (parent) parent.children.push(node); else roots.push(node)
  }
  const sort = (items: RunStepNode[]) => { items.sort((left, right) => left.ordinal - right.ordinal || left.uuid.localeCompare(right.uuid)); items.forEach((item) => sort(item.children)) }
  sort(roots)
  return roots
}

export function firstFailedPath(nodes: RunStepNode[]): string[] {
  for (const node of nodes) {
    if (failedStatuses.has(node.status)) return [node.uuid]
    const childPath = firstFailedPath(node.children)
    if (childPath.length) return [node.uuid, ...childPath]
  }
  return []
}
