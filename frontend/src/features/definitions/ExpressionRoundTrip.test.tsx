import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import '../../core/i18n'
import { ExpressionBuilder } from './ExpressionBuilder'

describe('mapping expression data-loss guard', () => {
  it('never applies a flattened multi-argument expression', () => {
    const value = { kind: 'CALL', function: 'COALESCE', args: [
      { kind: 'COLUMN', dataset: 'SRC', column: 'A' },
      { kind: 'LITERAL', value: 42 },
    ] }
    const before = JSON.stringify(value)
    const onApply = vi.fn()
    render(<ExpressionBuilder value={value} columns={[]} onApply={onApply} onCancel={vi.fn()} />)
    const apply = screen.getByRole('button', { name: /Apply|Uygula/i })
    expect(apply).toBeDisabled()
    fireEvent.click(apply)
    expect(onApply).not.toHaveBeenCalled()
    expect(JSON.stringify(value)).toBe(before)
  })

  it('round-trips a representable call without changing its AST', () => {
    const value = { kind: 'CALL', function: 'TRIM', args: [{ kind: 'COLUMN', dataset: 'SRC', column: 'A' }] }
    const onApply = vi.fn()
    render(<ExpressionBuilder value={value} columns={[{ dataset: 'SRC', column: 'A', label: 'SRC.A' }]} onApply={onApply} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Apply|Uygula/i }))
    expect(onApply).toHaveBeenCalledWith(value)
  })
})
