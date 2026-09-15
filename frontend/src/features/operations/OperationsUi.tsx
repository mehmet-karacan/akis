import { Button as AntActionButton } from '../../core/ui/Button'
import { Check, Clipboard } from 'lucide-react'
import { useState, type PropsWithChildren, type ReactNode } from 'react'
import { AsyncState, Field as CoreField, PageHeader as CorePageHeader, StatusBadge as CoreStatusBadge } from '../../core/ui'
import { Dialog as CoreDialog } from '../../core/ui/Dialog'
import { useOperationsI18n } from './i18n'
import './operations.css'
import { Card } from 'antd'

export function PageHeader({ title, description, actions }: {
  title: string
  description: string
  actions?: ReactNode
}) {
  return <CorePageHeader title={title} description={description} actions={actions} className="ops-page-header" />
}

export function Panel({ title, children, className = '' }: PropsWithChildren<{
  title?: string
  className?: string
}>) {
  return (
    <Card className={`ops-panel ${className}`} title={title}>
      {children}
    </Card>
  )
}

export function LoadingState() {
  const { t } = useOperationsI18n()
  return <AsyncState state="loading" title={t('loading')} className="ops-state" />
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useOperationsI18n()
  return <AsyncState state="error" title={message} retryLabel={t('retry')} onRetry={onRetry} className="ops-alert ops-alert-error" compact />
}

export function EmptyState({ children }: PropsWithChildren) {
  return <AsyncState state="empty" title={children} className="ops-state ops-empty" />
}

export function Field({ label, error, children, hint }: PropsWithChildren<{
  label: string
  error?: string
  hint?: string
}>) {
  return <CoreField label={label} hint={hint} error={error} className="ops-field">{children}</CoreField>
}

export function StatusBadge({ value }: { value: string }) {
  const { t } = useOperationsI18n()
  const key = `status_${value}` as Parameters<typeof t>[0]
  const known = ['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(value)
  const tone = ['AKTIF', 'ETKIN'].includes(value) ? 'success' : value === 'ONAY_BEKLIYOR' ? 'warning' : value === 'IPTAL' ? 'danger' : 'neutral'
  return <CoreStatusBadge tone={tone} className={`ops-status ops-status-${value.toLowerCase().replaceAll('_', '-')}`}>{known ? t(key) : value}</CoreStatusBadge>
}

export function CopyValue({ value }: { value: string }) {
  const { t } = useOperationsI18n()
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard.writeText(value)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1800)
  }
  return (
    <span className="ops-copy-value">
      <code title={value}>{value}</code>
      <AntActionButton tone="ghost" type="button" onClick={() => void copy()} aria-label={copied ? t('copied') : t('copy')}>
        {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
      </AntActionButton>
    </span>
  )
}

export function Dialog({ title, onClose, children }: PropsWithChildren<{
  title: string
  onClose: () => void
}>) {
  const { t } = useOperationsI18n()
  return <CoreDialog open title={title} onClose={onClose} closeLabel={t('close')} className="ops-dialog">{children}</CoreDialog>
}
