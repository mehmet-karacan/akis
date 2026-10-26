import type { SiraTanimi } from './api'

/** A table card shows only its own identity sequence, not sequences from similarly named tables. */
export function findSequencesForTable(tableName: string | undefined, sequences: SiraTanimi[]) {
  if (!tableName) return []
  return sequences.filter((sequence) => sequence.ad === `${tableName}_id_seq`)
}

/** A column owns only the PostgreSQL sequence with its exact table-column name. */
export function findSequenceForColumn(tableName: string | undefined, columnName: string, sequences: SiraTanimi[]) {
  if (!tableName) return undefined
  return sequences.find((sequence) => sequence.ad === `${tableName}_${columnName}_seq`)
}
