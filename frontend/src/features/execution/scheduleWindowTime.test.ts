import { describe, expect, it } from 'vitest'
import { fromZonedWallTime, toZonedWallTime } from './scheduleWindowTime'

describe('schedule window time', () => {
  it('uses the selected schedule zone, not the browser zone', () => {
    expect(fromZonedWallTime('2026-09-27T09:30', 'Europe/Istanbul')).toBe('2026-09-27T06:30:00.000Z')
    expect(toZonedWallTime('2026-09-27T06:30:00Z', 'Europe/Istanbul')).toBe('2026-09-27T09:30')
  })

  it('rejects missing and ambiguous DST wall times', () => {
    expect(() => fromZonedWallTime('2026-03-29T02:30', 'Europe/Berlin')).toThrow('mevcut değil')
    expect(() => fromZonedWallTime('2026-10-25T02:30', 'Europe/Berlin')).toThrow('iki kez')
  })

  it('rejects calendar-invalid inputs', () => {
    expect(() => fromZonedWallTime('2026-02-30T10:00', 'UTC')).toThrow('Geçerli')
  })
})
