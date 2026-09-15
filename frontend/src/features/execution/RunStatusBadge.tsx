import type { RunStatus } from './types'
import { useExecutionI18n, type ExecutionMessageKey } from './i18n'
import { Tag } from 'antd'

export const canonicalRunStatuses = [
  'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
  'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
  'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
  'BASARILI', 'BASARISIZ', 'IPTAL',
  'HATA_DEVAM', 'ATLANDI', 'KAYDEDILMEDI',
] as const

export function RunStatusBadge({ status }: { status: RunStatus }) {
  const { t } = useExecutionI18n()
  const known = canonicalRunStatuses.includes(status as typeof canonicalRunStatuses[number])
  const label = known ? t(`status_${status}` as ExecutionMessageKey) : status
  const color = status === 'BASARILI' ? 'success' : ['BASARISIZ', 'MUDAHALE_GEREKLI'].includes(status) ? 'error' : ['CALISIYOR', 'HAZIRLANIYOR', 'YAYINLANIYOR'].includes(status) ? 'processing' : ['SONUC_BELIRSIZ', 'HATA_DEVAM'].includes(status) ? 'warning' : 'default'
  return <Tag color={color} className={`run-status run-status--${status.toLowerCase().replaceAll('_', '-')}`}>{label}</Tag>
}
