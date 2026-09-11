import { Check, Clipboard, LoaderCircle, RefreshCw, TriangleAlert } from 'lucide-react'
import { useState, type PropsWithChildren, type ReactNode } from 'react'
import { Dialog as CoreDialog } from '../../core/ui/Dialog'
import { useOperationsI18n } from './i18n'
import './operations.css'

export function PageHeader({ title, description, actions }: {
  title: string
  description: string
  actions?: ReactNode
}) {
  return (
    <header className="ops-page-header">
      <div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions ? <div className="ops-page-actions">{actions}</div> : null}
    </header>
  )
}

export function Panel({ title, children, className = '' }: PropsWithChildren<{
  title?: string
  className?: string
}>) {
  return (
    <section className={`ops-panel ${className}`}>
      {title ? <h2>{title}</h2> : null}
      {children}
    </section>
  )
}

export function LoadingState() {
  const { t } = useOperationsI18n()
  return (
    <div className="ops-state" role="status" aria-live="polite">
      <LoaderCircle className="ops-spin" aria-hidden="true" />
      <span>{t('loading')}</span>
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useOperationsI18n()
  return (
    <div className="ops-alert ops-alert-error" role="alert">
      <TriangleAlert aria-hidden="true" />
      <span>{message}</span>
      <button className="ops-button ops-button-secondary" type="button" onClick={onRetry}>
        <RefreshCw aria-hidden="true" /> {t('retry')}
      </button>
    </div>
  )
}

export function EmptyState({ children }: PropsWithChildren) {
  return <div className="ops-state ops-empty">{children}</div>
}

export function Field({ label, error, children, hint }: PropsWithChildren<{
  label: string
  error?: string
  hint?: string
}>) {
  return (
    <label className="ops-field">
      <span>{label}</span>
      {children}
      {hint && !error ? <small>{hint}</small> : null}
      {error ? <small className="ops-field-error">{error}</small> : null}
    </label>
  )
}

export function StatusBadge({ value }: { value: string }) {
  const { t } = useOperationsI18n()
  const key = `status_${value}` as Parameters<typeof t>[0]
  const known = ['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(value)
  return (
    <span className={`ops-status ops-status-${value.toLowerCase().replaceAll('_', '-')}`}>
      {known ? t(key) : value}
    </span>
  )
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
      <button type="button" onClick={() => void copy()} aria-label={copied ? t('copied') : t('copy')}>
        {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
      </button>
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
