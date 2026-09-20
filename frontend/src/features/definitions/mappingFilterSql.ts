import type { MappingContent, MappingFilter } from './types'
import { mappingSqlText } from './mappingSqlText'

/** Opening a legacy filter is read-only; conversion occurs only after explicit Apply. */
export function mappingFilterSql(filter: MappingFilter, value: MappingContent): string {
  const aliases = Object.fromEntries(value.sources.map(source => [source.id, source.alias]))
  if (filter.predicate) return mappingSqlText(filter.predicate, aliases)
  const reference = `${aliases[filter.object] ?? filter.object}.${filter.column}`
  const literal = `'${(filter.value ?? '').replaceAll("'", "''")}'`
  switch (filter.operator) {
    case 'IS_NULL': return `${reference} IS NULL`
    case 'IS_NOT_NULL': return `${reference} IS NOT NULL`
    case 'LIKE': return `${reference} LIKE ${literal} ESCAPE '\\'`
    case 'EQUALS': return `${reference} = ${literal}`
    case 'NOT_EQUALS': return `${reference} <> ${literal}`
    case 'GREATER_THAN': return `${reference} > ${literal}`
    case 'LESS_THAN': return `${reference} < ${literal}`
    default: throw new Error('Unsupported filter')
  }
}

export function nextFilterId(filters: MappingFilter[]): string {
  let next = 1
  while (filters.some(filter => filter.id === `FILTER_${next}`)) next++
  return `FILTER_${next}`
}
