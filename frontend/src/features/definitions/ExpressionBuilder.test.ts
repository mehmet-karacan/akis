import { describe, expect, it } from 'vitest'
import { expressionSummary } from './ExpressionBuilder'

describe('expression AST summary', () => {
  it('round-trips supported nested column calls without exposing JSON', () => {
    const expression = { kind: 'CALL', function: 'TRIM', args: [{ kind: 'COLUMN', dataset: 'SRC', column: 'NAME' }] }
    expect(expressionSummary(expression)).toBe('TRIM(SRC.NAME)')
  })
  it('does not pretend an unknown AST is supported', () => expect(expressionSummary({ kind: 'SCRIPT', code: 'x' })).toBeNull())
})
