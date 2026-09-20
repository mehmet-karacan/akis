const sensitiveKey = /(?:password|passwd|secret|token|credential|private.?key|client.?secret)/i

export function redactSensitiveText(value: string) {
  return value
    .replace(/((?:password|passwd|secret|token|credential|client[_-]?secret)\s*[:=]\s*)([^\s,;&]+)/gi, '$1[REDACTED]')
    .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [REDACTED]')
    .replace(/:\/\/[^\s/@:]+:[^\s/@]+@/g, '://[REDACTED]@')
}

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
  return typeof value === 'string' ? redactSensitiveText(value) : value
}

export function formatDate(value: string | null | undefined, locale: string, fallback = '-') {
  return formatDateTime(value, locale, fallback)
}

export const isUuid = (value: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.trim())

export function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? redactSensitiveText(error.message) : fallback
}

export function toOffsetDateTime(value: string) {
  return value ? new Date(value).toISOString() : null
}
import { formatDateTime } from '../../core/i18n/formatters'
