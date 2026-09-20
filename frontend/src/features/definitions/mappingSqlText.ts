import type { MappingContent } from './types'
import type { SchemaSnapshotColumn } from '../topology/api'

/** Display only. Execution always uses the validated AST, never this string. */
export function mappingSqlText(expression: Record<string, unknown> | undefined, aliases: Record<string, string>): string {
  let remaining = 1000
  const visit = (value: unknown, depth: number): string => {
    if (!value || typeof value !== 'object' || Array.isArray(value) || depth > 32 || --remaining < 0) throw new Error('Unsupported SQL expression')
    const node = value as Record<string, unknown>
    const child = (key: string) => visit(node[key], depth + 1)
    switch (node.kind) {
      case 'COLUMN': return `${aliases[String(node.dataset)] ?? node.dataset}.${node.column}`
      case 'LITERAL': return node.value == null ? 'NULL' : typeof node.value === 'string' ? `'${node.value.replaceAll("'", "''")}'` : String(node.value)
      case 'NUMBER': if (typeof node.value === 'string' && /^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(node.value)) return node.value; break
      case 'CALL': if (Array.isArray(node.args)) return `${node.function}(${node.args.map(arg => visit(arg, depth + 1)).join(', ')})`; break
      case 'BINARY': return `(${child('left')} ${node.operator} ${child('right')}${typeof node.escape === 'string' ? ` ESCAPE '${node.escape.replaceAll("'", "''")}'` : ''})`
      case 'UNARY': return String(node.operator).startsWith('IS ') ? `(${child('argument')} ${node.operator})` : `(${node.operator} ${child('argument')})`
      case 'IN': if (Array.isArray(node.values)) return `(${child('argument')} ${node.negated ? 'NOT IN' : 'IN'} (${node.values.map(arg => visit(arg, depth + 1)).join(', ')}))`; break
      case 'BETWEEN': return `(${child('argument')} ${node.negated ? 'NOT BETWEEN' : 'BETWEEN'} ${child('lower')} AND ${child('upper')})`
      case 'CASE': if (Array.isArray(node.branches)) return `(CASE ${node.branches.map(branch => `WHEN ${visit(branch.when, depth + 1)} THEN ${visit(branch.then, depth + 1)}`).join(' ')} ELSE ${child('else')} END)`; break
    }
    throw new Error('Unsupported SQL expression')
  }
  return expression ? visit(expression, 0) : ''
}

export function mappingColumnCompletions(text: string, cursor: number, value: MappingContent, columns: Record<string, SchemaSnapshotColumn[]>) {
  const before = text.slice(0, cursor)
  let quoted = false
  for (let index = 0; index < before.length; index++) if (before[index] === "'") {
    if (quoted && before[index + 1] === "'") index++
    else quoted = !quoted
  }
  if (quoted) return null
  const match = /\b([A-Za-z][A-Za-z0-9_$#]*)\.([A-Za-z0-9_$#]*)$/.exec(before)
  const alias = match?.[1], partial = match?.[2]
  if (!alias || partial === undefined) return null
  const source = value.sources.find(item => item.alias.toUpperCase() === alias.toUpperCase())
  if (!source) return null
  return { from: cursor - partial.length, options: (columns[source.id] ?? []).map(column => ({ label: column.reference, type: 'property', detail: column.producerType })) }
}
