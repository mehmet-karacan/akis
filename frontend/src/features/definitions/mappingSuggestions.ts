import type { SchemaSnapshot } from '../topology/api'
import { typesCompatible } from './mappingColumnCompatibility'
import type { ColumnMapping, MappingContent } from './types'

type Columns = Record<string, SchemaSnapshot['columns']>
export type MappingSuggestion = Required<Pick<ColumnMapping, 'source' | 'target'>>

/** Only one named candidate is safe to propose. Type compatibility must not
 * silently choose between identically named columns from different sources. */
export function suggestColumnMappings(value: MappingContent, columns: Columns): MappingSuggestion[] {
  const candidates = value.sources.flatMap(source => (columns[source.id] ?? []).map(column => ({ source, column })))
  return (columns[value.target.id] ?? []).flatMap(target => {
    const existing = value.columnMappings.filter(row => row.target.object === value.target.id && row.target.column === target.reference)
    if (existing.length > 1 || existing.some(row => row.expression || row.source?.column)) return []
    const matches = candidates.filter(candidate => candidate.column.reference.toUpperCase() === target.reference.toUpperCase())
    if (matches.length !== 1) return []
    const match = matches[0]
    if (!match || !match.column.canonicalType || !target.canonicalType || !typesCompatible(match.column.canonicalType, target.canonicalType)) return []
    return [{ source: { object: match.source.id, column: match.column.reference }, target: { object: value.target.id, column: target.reference } }]
  })
}

export function applyColumnSuggestions(value: MappingContent, columns: Columns, suggestions: MappingSuggestion[]): MappingContent {
  const eligible = suggestColumnMappings(value, columns)
  const accepted = suggestions.filter(suggestion => eligible.some(candidate =>
    candidate.target.object === suggestion.target.object && candidate.target.column === suggestion.target.column &&
    candidate.source.object === suggestion.source.object && candidate.source.column === suggestion.source.column))
  const columnMappings = value.columnMappings.map(row => {
    const match = accepted.find(candidate => candidate.target.object === row.target.object && candidate.target.column === row.target.column)
    return match ? { ...row, source: match.source } : row
  })
  for (const suggestion of accepted) {
    if (!columnMappings.some(row => row.target.object === suggestion.target.object && row.target.column === suggestion.target.column)) columnMappings.push(suggestion)
  }
  return { ...value, columnMappings }
}

export function nextJoinId(joins: MappingContent['joins']): string {
  const ids = new Set(joins.map(join => join.id))
  let number = joins.length + 1
  while (ids.has(`JOIN_${number}`)) number++
  return `JOIN_${number}`
}
