export type Frequency = 'MINUTE_INTERVAL' | 'INTERVAL' | 'HOURLY' | 'DAILY' | 'MULTI_HOUR' | 'WEEKLY' | 'MONTHLY' | 'CUSTOM'

export function frequencyOf(cron: string): Frequency {
  const value = cron.trim()
  const minuteInterval = value.match(/^0 \*\/(\d+) \* \* \* \*$/)
  if (minuteInterval && inRange(minuteInterval[1], 1, 59)) return 'MINUTE_INTERVAL'
  const hourInterval = value.match(/^0 (\d+) \*\/(\d+) \* \* \*$/)
  if (hourInterval && inRange(hourInterval[1], 0, 59) && inRange(hourInterval[2], 1, 23)) return 'INTERVAL'
  const hourly = value.match(/^0 (\d+) \* \* \* \*$/)
  if (hourly && inRange(hourly[1], 0, 59)) return 'HOURLY'
  const daily = value.match(/^0 (\d+) (\d+) \* \* \*$/)
  if (daily && inRange(daily[1], 0, 59) && inRange(daily[2], 0, 23)) return 'DAILY'
  const multipleHours = value.match(/^0 (\d+) ((?:\d+,)+\d+) \* \* \*$/)
  if (multipleHours && inRange(multipleHours[1], 0, 59) && isCanonicalList(multipleHours[2], 0, 23)) return 'MULTI_HOUR'
  const weekly = value.match(/^0 (\d+) (\d+) \* \* ([1-7](?:,[1-7])*)$/)
  const weeklyDays = weekly?.[3]
  if (weekly && inRange(weekly[1], 0, 59) && inRange(weekly[2], 0, 23)
      && isCanonicalList(weeklyDays, 1, 7)) return 'WEEKLY'
  const monthly = value.match(/^0 (\d+) (\d+) (\d+) \* \?$/)
  if (monthly && inRange(monthly[1], 0, 59) && inRange(monthly[2], 0, 23) && inRange(monthly[3], 1, 31)) return 'MONTHLY'
  return 'CUSTOM'
}

function inRange(value: string | undefined, min: number, max: number): boolean {
  if (value === undefined) return false
  return /^(0|[1-9]\d*)$/.test(value) && Number(value) >= min && Number(value) <= max
}

function isCanonicalList(value: string | undefined, min: number, max: number): boolean {
  if (!value) return false
  const fields = value.split(',')
  return fields.every(field => inRange(field, min, max))
    && value === [...new Set(fields.map(Number))].sort((a, b) => a - b).join(',')
}

export function hoursOf(cron: string): number[] {
  const field = cron.trim().split(/\s+/)[2] || ''
  return isCanonicalList(field, 0, 23) ? field.split(',').map(Number) : [9, 17]
}

export function describeSchedule(cron: string, zone: string, tr: boolean): string {
  const frequency = frequencyOf(cron)
  const fields = cron.trim().split(/\s+/)
  const minute = Number(fields[1]); const hour = Number(fields[2])
  const time = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  const summary = (() => {
    switch (frequency) {
      case 'MINUTE_INTERVAL': return tr ? `Her ${minuteIntervalOf(cron)} dakikada bir` : `Every ${minuteIntervalOf(cron)} minutes`
      case 'INTERVAL': {
        const interval = intervalHoursOf(cron)
        if (24 % interval === 0) return tr ? `Her ${interval} saatte bir, ${String(minute).padStart(2, '0')}. dakikada` : `Every ${interval} hours at minute ${String(minute).padStart(2, '0')}`
        const times = Array.from({ length: Math.ceil(24 / interval) }, (_, index) => `${String(index * interval).padStart(2, '0')}:${String(minute).padStart(2, '0')}`).join(', ')
        return tr ? `Her gün ${times} (gün başında sıfırlanır)` : `Daily at ${times} (resets at midnight)`
      }
      case 'HOURLY': return tr ? `Her saatin ${minute}. dakikasında` : `Every hour at minute ${minute}`
      case 'DAILY': return tr ? `Her gün ${time}` : `Every day at ${time}`
      case 'MULTI_HOUR': return tr ? `Her gün ${hoursOf(cron).map(value => `${String(value).padStart(2, '0')}:${String(minute).padStart(2, '0')}`).join(', ')}` : `Every day at ${hoursOf(cron).map(value => `${String(value).padStart(2, '0')}:${String(minute).padStart(2, '0')}`).join(', ')}`
      case 'WEEKLY': {
        const names = tr ? ['Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma', 'Cumartesi', 'Pazar'] : ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
        const days = weekdaysOf(cron).map(value => names[value - 1]).join(', ')
        return tr ? `Her ${days} ${time}` : `Every ${days} at ${time}`
      }
      case 'MONTHLY': return tr ? `Her ayın ${fields[3]}. günü ${time}` : `Day ${fields[3]} of each month at ${time}`
      default: return tr ? 'Özel cron ifadesi' : 'Custom cron expression'
    }
  })()
  return `${summary} · ${zone}`
}

export function minuteIntervalOf(cron: string): number {
  const value = Number(cron.trim().match(/^0 \*\/(\d+) \* \* \* \*$/)?.[1])
  return Number.isInteger(value) && value >= 1 && value <= 59 ? value : 15
}

export function weekdaysOf(cron: string): number[] {
  const field = cron.trim().split(/\s+/)[5] || '1'
  return /^[1-7](?:,[1-7])*$/.test(field) ? [...new Set(field.split(',').map(Number))] : [1]
}

export function intervalHoursOf(cron: string): number {
  const match = cron.trim().match(/^0 \d+ \*\/(\d+) \* \* \*$/)
  const value = Number(match?.[1])
  return Number.isInteger(value) && value >= 1 && value <= 23 ? value : 2
}

export function cronFor(frequency: Frequency, hour: number, minute: number, weekday: number | number[], monthday: number, intervalHours = 2, minuteInterval = 15, multipleHours: number[] = [9, 17]): string {
  const days = Array.isArray(weekday) ? weekday : [weekday]
  return ({
    MINUTE_INTERVAL: `0 */${Math.min(59, Math.max(1, minuteInterval))} * * * *`,
    INTERVAL: `0 ${minute} */${Math.min(23, Math.max(1, intervalHours))} * * *`,
    HOURLY: `0 ${minute} * * * *`,
    DAILY: `0 ${minute} ${hour} * * *`,
    MULTI_HOUR: `0 ${minute} ${multipleHours.join(',')} * * *`,
    WEEKLY: `0 ${minute} ${hour} * * ${days.join(',')}`,
    MONTHLY: `0 ${minute} ${hour} ${monthday} * ?`,
    CUSTOM: '',
  })[frequency]
}
