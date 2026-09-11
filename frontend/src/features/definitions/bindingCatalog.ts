import type { BindingCandidate, DataBinding, DefinitionType } from './types'

export interface BindingNode {
  code: string
  name: string
  role: 'KAYNAK' | 'HEDEF'
}

function records(value: unknown, field: string): Record<string, unknown>[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  const collection = (value as Record<string, unknown>)[field]
  return Array.isArray(collection)
    ? collection.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object' && !Array.isArray(item))
    : []
}

export function bindingNodes(type: DefinitionType, content: unknown): BindingNode[] {
  if (type === 'PROCEDURE') {
    return records(content, 'tasks').flatMap((task) => {
      const code = typeof task.id === 'string' ? task.id : ''
      const connectionRole = task.connectionRole
      if (!code || (connectionRole !== 'SOURCE' && connectionRole !== 'TARGET')) return []
      return [{
        code,
        name: typeof task.name === 'string' && task.name ? task.name : code,
        role: connectionRole === 'SOURCE' ? 'KAYNAK' as const : 'HEDEF' as const,
      }]
    })
  }
  if (type === 'MAPPING') {
    return records(content, 'datasets').flatMap((dataset) => {
      const code = typeof dataset.id === 'string' ? dataset.id : ''
      const role = dataset.role
      if (!code || (role !== 'SOURCE' && role !== 'TARGET')) return []
      return [{
        code,
        name: typeof dataset.name === 'string' && dataset.name ? dataset.name : code,
        role: role === 'SOURCE' ? 'KAYNAK' as const : 'HEDEF' as const,
      }]
    })
  }
  return []
}

export function unboundNodes(nodes: BindingNode[], bindings: DataBinding[]) {
  const bound = new Set(bindings.map((binding) => binding.nodeCode))
  return nodes.filter((node) => !bound.has(node.code))
}

export function candidateLabel(candidate: BindingCandidate) {
  const fingerprint = `${candidate.snapshotFingerprint.slice(0, 10)}…${candidate.snapshotFingerprint.slice(-6)}`
  return `${candidate.connectionCode} · ${candidate.physicalSchemaReference}.${candidate.objectReference} · v${candidate.connectionVersionNumber} · ${fingerprint}`
}
