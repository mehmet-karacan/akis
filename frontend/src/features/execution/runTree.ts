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

/** A design step as the procedure editor shows it: a SOURCE read feeding the next TARGET write is one step with two commands. */
export interface RunStepUnit { key: string; ordinal: number; name: string; status: string; commands: RunStepNode[]; children: RunStepNode[] }

const settledStatuses = new Set(['BASARILI', 'IPTAL', 'ATLANDI'])

function unitStatus(commands: RunStepNode[]): string {
  const failed = commands.find((item) => failedStatuses.has(item.status))
  if (failed) return failed.status
  const active = commands.find((item) => !settledStatuses.has(item.status))
  return active ? active.status : commands.at(-1)!.status
}

export function groupRunStepUnits(nodes: RunStepNode[], feeds: (sourceCode: string, targetCode: string) => boolean): RunStepUnit[] {
  const units: RunStepUnit[] = []
  for (let index = 0; index < nodes.length; index += 1) {
    const node = nodes[index]!
    const next = nodes[index + 1]
    const commands = next && node.connectionRole === 'SOURCE' && next.connectionRole === 'TARGET' && feeds(node.code, next.code) ? [node, next] : [node]
    if (commands.length === 2) index += 1
    units.push({ key: node.uuid, ordinal: units.length + 1, name: (commands.at(-1) ?? node).name, status: unitStatus(commands), commands, children: commands.flatMap((item) => item.children) })
  }
  return units
}
