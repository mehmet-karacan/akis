import { describe, expect, it } from 'vitest'
import { cronFor, describeSchedule, frequencyOf, hoursOf, intervalHoursOf, minuteIntervalOf, weekdaysOf } from './schedulePresets'

describe('schedule presets', () => {
  it('recognizes interval schedules and reads their hour interval', () => {
    expect(frequencyOf('0 0 */2 * * *')).toBe('INTERVAL')
    expect(intervalHoursOf('0 0 */2 * * *')).toBe(2)
  })

  it('creates an interval cron expression', () => {
    expect(cronFor('INTERVAL', 0, 15, 1, 1, 6)).toBe('0 15 */6 * * *')
  })

  it('keeps the existing standard presets intact', () => {
    expect(cronFor('HOURLY', 0, 5, 1, 1)).toBe('0 5 * * * *')
    expect(cronFor('DAILY', 7, 30, 1, 1)).toBe('0 30 7 * * *')
    expect(cronFor('WEEKLY', 9, 45, 3, 1)).toBe('0 45 9 * * 3')
    expect(cronFor('MONTHLY', 10, 0, 1, 15)).toBe('0 0 10 15 * ?')
    expect(frequencyOf('0 0 9 * * 3')).toBe('WEEKLY')
    expect(frequencyOf('0 0 9 * * *')).toBe('DAILY')
  })

  it('round-trips minute intervals, multiple weekdays, and month-end days', () => {
    expect(frequencyOf('0 */7 * * * *')).toBe('MINUTE_INTERVAL')
    expect(minuteIntervalOf('0 */7 * * * *')).toBe(7)
    expect(cronFor('MINUTE_INTERVAL', 0, 0, 1, 1, 2, 7)).toBe('0 */7 * * * *')
    expect(frequencyOf('0 30 9 * * 1,3,5')).toBe('WEEKLY')
    expect(weekdaysOf('0 30 9 * * 1,3,5')).toEqual([1, 3, 5])
    expect(cronFor('WEEKLY', 9, 30, [1, 3, 5], 1)).toBe('0 30 9 * * 1,3,5')
    expect(frequencyOf('0 0 10 31 * ?')).toBe('MONTHLY')
    expect(cronFor('MONTHLY', 10, 0, 1, 31)).toBe('0 0 10 31 * ?')
    expect(frequencyOf('0 15 8,12,18 * * *')).toBe('MULTI_HOUR')
    expect(hoursOf('0 15 8,12,18 * * *')).toEqual([8, 12, 18])
    expect(cronFor('MULTI_HOUR', 0, 15, 1, 1, 2, 15, [8, 12, 18])).toBe('0 15 8,12,18 * * *')
  })

  it('keeps expressions that the form cannot represent in custom mode', () => {
    for (const cron of ['0 60 * * * *', '0 0 */24 * * *', '0 0 25 * * *', '0 0 9 * * 5,1', '0 0 9 * * 1,1', '0 0 9 32 * ?', '0 0 9 * * MON-FRI', '0 0 17,8 * * *']) {
      expect(frequencyOf(cron)).toBe('CUSTOM')
    }
  })

  it('describes common schedules in plain language', () => {
    expect(describeSchedule('0 2 * * * *', 'Europe/Istanbul', true)).toBe('Her saatin 2. dakikasında · Europe/Istanbul')
    expect(describeSchedule('0 0 */2 * * *', 'UTC', true)).toBe('Her 2 saatte bir, 00. dakikada · UTC')
    expect(describeSchedule('0 30 9 * * 1,3', 'UTC', true)).toBe('Her Pazartesi, Çarşamba 09:30 · UTC')
    expect(describeSchedule('0 0 */5 * * *', 'UTC', true)).toBe('Her gün 00:00, 05:00, 10:00, 15:00, 20:00 (gün başında sıfırlanır) · UTC')
  })
})
