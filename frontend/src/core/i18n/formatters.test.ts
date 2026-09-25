import { describe, expect, it } from 'vitest'
import { formatOperationalDuration } from './formatters'

describe('formatOperationalDuration', () => {
  it('rounds a positive sub-second run up to one second', () => {
    expect(formatOperationalDuration('2026-09-24T22:05:30.100Z', '2026-09-24T22:05:30.900Z', 'tr')).toBe('1 Saniye')
  })

  it('omits empty units', () => {
    expect(formatOperationalDuration('2026-09-24T20:00:00Z', '2026-09-25T22:03:04Z', 'tr')).toBe('1 Gün 2 Saat 3 Dakika 4 Saniye')
  })
})
