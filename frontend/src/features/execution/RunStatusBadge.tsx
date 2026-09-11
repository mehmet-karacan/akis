import type { RunStatus } from './types'
import { useExecutionI18n, type ExecutionMessageKey } from './i18n'

export const canonicalRunStatuses = [
  'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
  'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
  'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
  'BASARILI', 'BASARISIZ', 'IPTAL',
] as const

export function RunStatusBadge({ status }: { status: RunStatus }) {
  const { t } = useExecutionI18n()
  const known = canonicalRunStatuses.includes(status as typeof canonicalRunStatuses[number])
  const label = known ? t(`status_${status}` as ExecutionMessageKey) : status
  return <span className={`run-status run-status--${status.toLowerCase().replaceAll('_', '-')}`}>{label}</span>
}
