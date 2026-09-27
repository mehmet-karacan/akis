import { describe, expect, it } from 'vitest'
import { formatScheduleDate, schedulePublicationSummary } from './scheduleFormatters'

describe('formatScheduleDate', () => {
  it('formats an occurrence in the schedule timezone rather than the browser timezone', () => {
    const value = '2026-09-26T20:00:00.000Z'
    expect(formatScheduleDate(value, 'en-US', 'UTC')).toContain('8:00 PM')
    expect(formatScheduleDate(value, 'en-US', 'Europe/Istanbul')).toContain('11:00 PM')
  })

  it('returns a safe fallback for missing or invalid values', () => {
    expect(formatScheduleDate(null, 'tr-TR', 'Europe/Istanbul')).toBe('-')
    expect(formatScheduleDate('not-a-date', 'tr-TR', 'Europe/Istanbul')).toBe('-')
  })
})

describe('schedulePublicationSummary', () => {
  it('distinguishes the configured anchor from a pinned publication', () => {
    expect(schedulePublicationSummary('LATEST_ACTIVE', '#2 · DEV', 'tr-TR')).toBe('Son aktif yayın · Dayanak: #2 · DEV')
    expect(schedulePublicationSummary('PINNED', '#2 · DEV', 'tr-TR')).toBe('Sabit yayın · #2 · DEV')
  })
})
