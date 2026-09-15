import { describe, expect, it } from 'vitest'
import { canEditExpression, expressionSummary } from './ExpressionBuilder'

describe('expression AST summary', () => {
  it('protects multi-argument, nested and typed literals from lossy editing', () => {
    const column = { kind: 'COLUMN', dataset: 'SRC', column: 'NAME' }
    expect(canEditExpression({ kind: 'CALL', function: 'COALESCE', args: [column, { kind: 'LITERAL', value: 'fallback' }] })).toBe(false)
    expect(canEditExpression({ kind: 'CALL', function: 'UPPER', args: [{ kind: 'CALL', function: 'TRIM', args: [column] }] })).toBe(false)
    for (const value of [null, 42, true]) expect(canEditExpression({ kind: 'LITERAL', value })).toBe(false)
    expect(canEditExpression({ ...column, type: 'VARCHAR' })).toBe(false)
    expect(canEditExpression({ kind: 'CALL', function: 'TRIM', args: [column] })).toBe(true)
    expect(canEditExpression({ kind: 'LITERAL', value: 'text' })).toBe(true)
  })
  it('round-trips supported nested column calls without exposing JSON', () => {
    const expression = { kind: 'CALL', function: 'TRIM', args: [{ kind: 'COLUMN', dataset: 'SRC', column: 'NAME' }] }
    expect(expressionSummary(expression)).toBe('TRIM(SRC.NAME)')
  })
  it('does not pretend an unknown AST is supported', () => expect(expressionSummary({ kind: 'SCRIPT', code: 'x' })).toBeNull())
})
