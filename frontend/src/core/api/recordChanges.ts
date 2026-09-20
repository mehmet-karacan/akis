export const recordChangedEvent = 'akis:record-changed'
export type RecordKind = 'connections' | 'logical-schemas' | 'environments' | 'physical-schemas' | 'models' | 'data-objects' | 'definitions' | 'folders'
export interface RecordChange { projectUuid: string; kind: RecordKind }
const kinds = new Set<RecordKind>(['connections', 'logical-schemas', 'environments', 'physical-schemas', 'models', 'data-objects', 'definitions', 'folders'])

/** Invalidate attribution only for successful record writes, not SQL tests,
 * connection probes, validation requests or arbitrary POST endpoints.
 */
export function recordChangeFor(path: string, method: string): RecordChange | null {
  if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase())) return null
  const match = path.split('?')[0]?.match(/^\/api\/v[12]\/projects\/([^/]+)\/([^/]+)(.*)$/)
  if (!match) return null
  const [, project, collection, tail] = match
  let projectUuid: string
  try { projectUuid = decodeURIComponent(project!) } catch { return null }
  const parts = tail!.split('/').filter(Boolean)
  if (collection === 'models' && parts[1] === 'data-objects') {
    if (parts.length === 2 || (parts.length === 4 && parts[3] === 'folder')) return { projectUuid, kind: 'data-objects' }
    return null
  }
  if (!kinds.has(collection as RecordKind)) return null
  if (parts.length <= 1 || (parts.length === 2 && (
    (collection === 'definitions' && ['draft', 'move'].includes(parts[1]!)) ||
    (collection === 'folders' && parts[1] === 'move') ||
    (collection === 'connections' && parts[1] === 'versions')
  ))) return { projectUuid, kind: collection as RecordKind }
  return null
}
