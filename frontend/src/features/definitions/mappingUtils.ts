import type { ColumnMapping } from './types'

export const MAPPING_PAGE_SIZE = 50

export function mappingRowText(row: ColumnMapping): string {
  const expression = row.expression ? JSON.stringify(row.expression) : ''
  return [
    row.source?.object,
    row.source?.column,
    expression,
    row.target.object,
    row.target.column,
  ]
    .filter(Boolean)
    .join(' ')
    .toLocaleLowerCase()
}

export function filterMappingRows(rows: ColumnMapping[], query: string) {
  const normalized = query.trim().toLocaleLowerCase()
  if (!normalized) return rows.map((row, index) => ({ row, index }))
  return rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => mappingRowText(row).includes(normalized))
}

export function pageCount(rowCount: number, pageSize = MAPPING_PAGE_SIZE) {
  return Math.max(1, Math.ceil(rowCount / pageSize))
}

export function safePage(page: number, rowCount: number, pageSize = MAPPING_PAGE_SIZE) {
  return Math.min(Math.max(0, page), pageCount(rowCount, pageSize) - 1)
}
