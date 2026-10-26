import type { SiraTanimi } from './api'

/** A column owns only the PostgreSQL sequence with its exact table-column name. */
export function findSequenceForColumn(tableName: string | undefined, columnName: string, sequences: SiraTanimi[]) {
  if (!tableName) return undefined
  return sequences.find((sequence) => sequence.ad === `${tableName}_${columnName}_seq`)
}
