export function formatScheduleDate(value: string | null | undefined, locale: string, timeZone: string, fallback = '-') {
  if (!value) return fallback
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(value))
  } catch {
    return fallback
  }
}

export function schedulePublicationSummary(policy: 'LATEST_ACTIVE' | 'PINNED', configuredMeta: string, locale: string) {
  const tr = locale.startsWith('tr')
  if (policy === 'PINNED') return `${tr ? 'Sabit yayın' : 'Pinned publication'}${configuredMeta ? ` · ${configuredMeta}` : ''}`
  return `${tr ? 'Son aktif yayın' : 'Latest active publication'}${configuredMeta ? ` · ${tr ? 'Dayanak' : 'Anchor'}: ${configuredMeta}` : ''}`
}
