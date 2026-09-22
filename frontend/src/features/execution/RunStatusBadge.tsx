import type { RunStatus } from './types'
import { useExecutionI18n, type ExecutionMessageKey } from './i18n'
import { Tag } from 'antd'
import type { ReactNode } from 'react'
import { CheckCircle2, CircleAlert, Clock3, XCircle } from 'lucide-react'

export const canonicalRunStatuses = [
  'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
  'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
  'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
  'BASARILI', 'BASARISIZ', 'IPTAL',
  'HATA_DEVAM', 'ATLANDI', 'KAYDEDILMEDI',
] as const

/** `label` overrides the canonical text while keeping the status colour (e.g. a package step adopted from the previous attempt). */
export function RunStatusBadge({ status, label: override }: { status: RunStatus; label?: string }) {
  const { t } = useExecutionI18n()
  const known = canonicalRunStatuses.includes(status as typeof canonicalRunStatuses[number])
  const label = override ?? (known ? t(`status_${status}` as ExecutionMessageKey) : status)
  const color = status === 'BASARILI' ? 'success' : ['BASARISIZ', 'MUDAHALE_GEREKLI'].includes(status) ? 'error' : ['CALISIYOR', 'HAZIRLANIYOR', 'YAYINLANIYOR'].includes(status) ? 'processing' : ['SONUC_BELIRSIZ', 'HATA_DEVAM'].includes(status) ? 'warning' : 'default'
  return <Tag color={color} className={`run-status run-status--${status.toLowerCase().replaceAll('_', '-')}`}>{label}</Tag>
}

export type RunStatusTone = 'success' | 'warning' | 'danger' | 'neutral'

/** Label, tone and icon for the shared colored status tag used by catalog screens. */
export function runStatusPresentation(status: string, t: (key: ExecutionMessageKey) => string): { label: string; tone: RunStatusTone; icon: ReactNode } {
  const known = canonicalRunStatuses.includes(status as typeof canonicalRunStatuses[number])
  const label = known ? t(`status_${status}` as ExecutionMessageKey) : status
  const tone: RunStatusTone = status === 'BASARILI' ? 'success'
    : ['BASARISIZ', 'MUDAHALE_GEREKLI', 'IPTAL'].includes(status) ? 'danger'
    : ['CALISIYOR', 'HAZIRLANIYOR', 'YAYINLANIYOR', 'BEKLIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT', 'SONUC_BELIRSIZ', 'HATA_DEVAM', 'YENIDEN_DENENEBILIR'].includes(status) ? 'warning'
    : 'neutral'
  const icon = tone === 'success' ? <CheckCircle2 size={12} /> : tone === 'danger' ? <XCircle size={12} /> : tone === 'warning' ? <Clock3 size={12} /> : <CircleAlert size={12} />
  return { label, tone, icon }
}
