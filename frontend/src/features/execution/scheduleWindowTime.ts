/** Convert an absolute instant to the schedule zone's editable wall-clock minute. */
export function toZonedWallTime(instant: string, zone: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(instant))
  const part = (name: string) => parts.find(item => item.type === name)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`
}

/** Resolve wall time in the selected zone without accidentally using the browser's zone. */
export function fromZonedWallTime(value: string, zone: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) throw new Error('Geçerli bir tarih ve saat girin.')
  const year = Number(match[1]); const month = Number(match[2]); const day = Number(match[3])
  const hour = Number(match[4]); const minute = Number(match[5])
  const naive = Date.UTC(year, month - 1, day, hour, minute)
  if (!Number.isFinite(naive) || new Date(naive).toISOString().slice(0, 16) !== value) {
    throw new Error('Geçerli bir tarih ve saat girin.')
  }
  const offsets = [naive - 36 * 3600000, naive, naive + 36 * 3600000].map(at => offsetMs(at, zone))
  const candidates = [...new Set(offsets.map(offset => naive - offset))]
    .filter(at => toZonedWallTime(new Date(at).toISOString(), zone) === value)
  if (candidates.length === 0) throw new Error('Bu saat seçilen zaman diliminde mevcut değil.')
  if (candidates.length > 1) throw new Error('Bu saat yaz saati geçişinde iki kez yaşanıyor; farklı bir saat seçin.')
  return new Date(candidates[0]!).toISOString()
}

function offsetMs(instant: number, zone: string): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(instant))
  const part = (name: string) => Number(parts.find(item => item.type === name)?.value)
  return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - instant
}
