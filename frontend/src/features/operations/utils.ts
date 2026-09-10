const sensitiveKey = /(?:password|passwd|secret|token|credential|private.?key|client.?secret)/i

export function redactSensitiveValues(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redactSensitiveValues)
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [
        key,
        sensitiveKey.test(key) ? '[REDACTED]' : redactSensitiveValues(child),
      ]),
    )
  }
  return value
}

export function formatDate(value: string | null | undefined, locale: string, fallback = '—') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.valueOf())
    ? fallback
    : new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export const isUuid = (value: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.trim())

export function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

export function toOffsetDateTime(value: string) {
  return value ? new Date(value).toISOString() : null
}

