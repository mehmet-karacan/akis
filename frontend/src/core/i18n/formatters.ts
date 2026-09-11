export function normalizeLocale(language: string) {
  return language.toLocaleLowerCase().startsWith('tr') ? 'tr-TR' : 'en-GB'
}

export function formatDateTime(value: string | null | undefined, language: string, fallback = '—') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? fallback : new Intl.DateTimeFormat(normalizeLocale(language), { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export function formatDate(value: string | null | undefined, language: string, fallback = '—') {
  if (!value) return fallback
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? fallback : new Intl.DateTimeFormat(normalizeLocale(language), { dateStyle: 'medium' }).format(date)
}
