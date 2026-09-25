export function normalizeLocale(language: string) {
  return language.toLocaleLowerCase().startsWith('tr') ? 'tr-TR' : 'en-GB'
}

export function formatDateTime(value: string | null | undefined, language: string, fallback = '-') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? fallback : new Intl.DateTimeFormat(normalizeLocale(language), { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export function formatDate(value: string | null | undefined, language: string, fallback = '-') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? fallback : new Intl.DateTimeFormat(normalizeLocale(language), { dateStyle: 'medium' }).format(date)
}

export function formatOperationalDateTime(value: string | null | undefined, fallback = '-') {
  if (!value) return fallback
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return fallback
  const parts = new Intl.DateTimeFormat('tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('day')}.${part('month')}.${part('year')} ${part('hour')}:${part('minute')}:${part('second')}`
}

export function formatOperationalDuration(startedAt: string | null | undefined, finishedAt: string | null | undefined, language: string, fallback = '-') {
  if (!startedAt) return fallback
  const milliseconds = (finishedAt ? Date.parse(finishedAt) : Date.now()) - Date.parse(startedAt)
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return fallback
  // Operational durations must not display a positive sub-second run as "0 seconds".
  let remaining = milliseconds > 0 ? Math.ceil(milliseconds / 1000) : 0
  const values = [Math.floor(remaining / 86400), 0, 0, 0]
  remaining %= 86400; values[1] = Math.floor(remaining / 3600)
  remaining %= 3600; values[2] = Math.floor(remaining / 60); values[3] = remaining % 60
  const labels = language.toLocaleLowerCase().startsWith('tr')
    ? ['Gün', 'Saat', 'Dakika', 'Saniye']
    : ['Days', 'Hours', 'Minutes', 'Seconds']
  const number = new Intl.NumberFormat(normalizeLocale(language))
  const parts = values.map((value, index) => value ? `${number.format(value)} ${labels[index]}` : '').filter(Boolean)
  return parts.join(' ') || `0 ${labels[3]}`
}

export function formatNumber(value: number | bigint | string | null | undefined, language: string, fallback = '—') {
  if (value == null || value === '') return fallback
  if (typeof value === 'number') return Number.isFinite(value) ? new Intl.NumberFormat(normalizeLocale(language)).format(value) : fallback
  if (typeof value === 'bigint') return new Intl.NumberFormat(normalizeLocale(language)).format(value)
  if (!/^-?\d+$/.test(value)) return value
  try { return new Intl.NumberFormat(normalizeLocale(language)).format(BigInt(value)) } catch { return value }
}
