import type { SchemaSnapshotColumn } from './api'

/** One display contract for catalog rows, diagram nodes and column properties.
 * Database precision takes precedence over a driver's storage-byte length.
 */
export function columnSize(column: SchemaSnapshotColumn): string {
  if (column.precision != null) return column.scale != null ? `${column.precision}, ${column.scale}` : String(column.precision)
  if (column.timePrecision != null) return String(column.timePrecision)
  return column.length != null ? String(column.length) : ''
}

