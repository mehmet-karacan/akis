import { Binary, CalendarClock, CaseSensitive, Hash, ToggleLeft, Braces } from 'lucide-react'

export function DatabaseTypeIcon({ type }: { type: string }) {
  const normalized = type.toUpperCase()
  const Icon = /CHAR|TEXT|CLOB|STRING/.test(normalized) ? CaseSensitive
    : /NUMBER|NUMERIC|DECIMAL|INT|FLOAT|DOUBLE|REAL/.test(normalized) ? Hash
      : /DATE|TIME/.test(normalized) ? CalendarClock
        : /BOOL|BIT/.test(normalized) ? ToggleLeft
          : /BLOB|RAW|BINARY/.test(normalized) ? Binary : Braces
  return <Icon size={14} aria-hidden="true" />
}
