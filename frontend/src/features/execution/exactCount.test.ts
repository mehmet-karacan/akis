import { describe, expect, it } from 'vitest'
import { exactCount } from './types'

describe('exact execution counters', () => {
  it('preserves values above JavaScript safe integer range', () => {
    expect(exactCount('9007199254740993')).toBe(9007199254740993n)
  })

  it('keeps unknown distinct from zero', () => {
    expect(exactCount(null)).toBeNull()
    expect(exactCount('0')).toBe(0n)
  })
})
