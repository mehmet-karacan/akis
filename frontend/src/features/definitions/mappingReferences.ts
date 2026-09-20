import type { MappingContent } from './types'

export function expressionColumnReferences(expression: Record<string, unknown> | undefined): { object: string; column: string }[] {
  const found = new Map<string, { object: string; column: string }>()
  const valid = expressionColumnsValid(expression, (object, column) => { found.set(`${object}\0${column}`, { object, column }); return true })
  return valid ? [...found.values()] : []
}

/** Walk semantic column references, never SQL display text or literal values. */
export function expressionColumnsValid(expression: Record<string, unknown> | undefined, valid: (object: string, column: string) => boolean): boolean {
  let remaining = 1000
  const visit = (node: unknown, depth: number): boolean => {
    if (--remaining < 0 || depth > 32 || !node || typeof node !== 'object' || Array.isArray(node)) return false
    const item = node as Record<string, unknown>
    if (item.kind === 'LITERAL') return true
    if (item.kind === 'NUMBER') return typeof item.value === 'string'
    if (item.kind === 'COLUMN') return typeof item.dataset === 'string' && typeof item.column === 'string' && valid(item.dataset, item.column)
    if (item.kind === 'CALL') return Array.isArray(item.args) && item.args.every(argument => visit(argument, depth + 1))
    if (item.kind === 'UNARY') return visit(item.argument, depth + 1)
    if (item.kind === 'BINARY') return visit(item.left, depth + 1) && visit(item.right, depth + 1)
    if (item.kind === 'BETWEEN') return visit(item.argument, depth + 1) && visit(item.lower, depth + 1) && visit(item.upper, depth + 1)
    if (item.kind === 'IN') return visit(item.argument, depth + 1) && Array.isArray(item.values) && item.values.every(argument => visit(argument, depth + 1))
    if (item.kind === 'CASE') return visit(item.else, depth + 1) && Array.isArray(item.branches) && item.branches.every(branch => branch && typeof branch === 'object' && visit(branch.when, depth + 1) && visit(branch.then, depth + 1))
    return false
  }
  return expression === undefined || visit(expression, 0)
}

export function removeMappingObject(value: MappingContent, objectId: string): MappingContent {
  return {
    ...value,
    sources: value.sources.filter(source => source.id !== objectId),
    // Retain a target drop zone so another datastore can be assigned without a form.
    target: value.target.id === objectId ? { id: value.target.id, alias: value.target.alias } : value.target,
    columnMappings: value.columnMappings.filter(row => row.source?.object !== objectId && row.target.object !== objectId && expressionColumnsValid(row.expression, object => object !== objectId)),
    joins: value.joins.filter(join => join.left.object !== objectId && join.right.object !== objectId),
    filters: value.filters.filter(filter => filter.object !== objectId && expressionColumnsValid(filter.predicate, object => object !== objectId)),
  }
}
