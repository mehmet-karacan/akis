import type { DiscoveryColumn, SchemaSnapshotColumn } from '../topology/api'

export type MetadataChangeKind = 'ADDED' | 'CHANGED' | 'REMOVED'

export interface MetadataChange {
  kind: MetadataChangeKind
  name: string
  before?: SchemaSnapshotColumn
  after?: DiscoveryColumn
}

const key = (value: { name?: string; reference?: string }) => (value.reference ?? value.name ?? '').toLocaleUpperCase()

export function compareMetadata(previous: SchemaSnapshotColumn[], next: DiscoveryColumn[]): MetadataChange[] {
  const before = new Map(previous.map((column) => [key(column), column]))
  const after = new Map(next.map((column) => [key(column), column]))
  const changes: MetadataChange[] = []
  for (const [reference, column] of after) {
    const old = before.get(reference)
    if (!old) changes.push({ kind: 'ADDED', name: column.name, after: column })
    else if (old.producerType !== column.producerType || old.precision !== column.precision || old.scale !== column.scale || old.nullable !== column.nullable) changes.push({ kind: 'CHANGED', name: column.name, before: old, after: column })
  }
  for (const [reference, column] of before) if (!after.has(reference)) changes.push({ kind: 'REMOVED', name: column.reference, before: column })
  return changes.sort((left, right) => left.name.localeCompare(right.name))
}
