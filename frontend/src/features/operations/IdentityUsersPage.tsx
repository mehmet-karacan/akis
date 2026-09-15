import { Button as AntActionButton } from '../../core/ui/Button'
import { DataGrid } from '../../core/ui/DataGrid'
import { Input as AntInput } from 'antd'
import { Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { operationsApi } from './api'
import { Dialog, EmptyState, ErrorState, Field, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import { apiErrorMessage, formatDate } from './utils'
import { useRemoteData } from './useRemoteData'

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function IdentityUsersPage() {
  const { t, locale } = useOperationsI18n()
  const users = useRemoteData(() => operationsApi.listUsers(), [])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [issuer, setIssuer] = useState('')
  const [subject, setSubject] = useState('')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  const errors = {
    issuer: touched && !issuer.trim() ? t('required') : '',
    subject: touched && !subject.trim() ? t('required') : '',
    name: touched && !name.trim() ? t('required') : '',
    email: touched && email.trim() && !EMAIL.test(email.trim()) ? t('invalidEmail') : '',
  }
  const invalid = Object.values(errors).some(Boolean)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!issuer.trim() || !subject.trim() || !name.trim() || (email.trim() && !EMAIL.test(email.trim()))) return
    setSubmitting(true)
    setSubmitError('')
    try {
      await operationsApi.createUser({
        issuer: issuer.trim(),
        subject: subject.trim(),
        name: name.trim(),
        email: email.trim() || null,
      })
      setIssuer('')
      setSubject('')
      setName('')
      setEmail('')
      setTouched(false)
      setDialogOpen(false)
      await users.reload()
    } catch (error) {
      setSubmitError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="ops-page">
      <PageHeader
        title={t('users')}
        description={t('usersHelp')}
        actions={<AntActionButton tone="ghost" className="ops-button" type="button" onClick={() => setDialogOpen(true)}><Plus aria-hidden="true" /> {t('newUser')}</AntActionButton>}
      />
      <Panel>
        {users.loading ? <LoadingState /> : null}
        {!users.loading && users.error ? <ErrorState message={apiErrorMessage(users.error, t('requestFailed'))} onRetry={() => void users.reload()} /> : null}
        {!users.loading && !users.error && users.data?.length === 0 ? <EmptyState>{t('emptyUsers')}</EmptyState> : null}
        {!users.loading && users.data && users.data.length > 0 ? (
          <div className="ops-table-wrap">
            <DataGrid className="ops-table">
              <thead><tr><th scope="col">{t('name')}</th><th scope="col">{t('issuer')}</th><th scope="col">{t('subject')}</th><th scope="col">{t('status')}</th><th scope="col">{t('createdAt')}</th></tr></thead>
              <tbody>
                {users.data.map((user) => (
                  <tr key={user.uuid}>
                    <td>{user.name}<span className="ops-cell-secondary">{user.email || '—'}</span></td>
                    <td>{user.issuer}</td>
                    <td><code title={user.subject}>{user.subject}</code></td>
                    <td><StatusBadge value={user.status} /></td>
                    <td>{formatDate(user.createdAt, locale)}</td>
                  </tr>
                ))}
              </tbody>
            </DataGrid>
          </div>
        ) : null}
      </Panel>

      {dialogOpen ? (
        <Dialog title={t('newUser')} onClose={() => !submitting && setDialogOpen(false)}>
          <form className="ops-form" onSubmit={(event) => void submit(event)} noValidate>
            {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
            <div className="ops-form-grid">
              <Field label={t('issuer')} error={errors.issuer}><AntInput value={issuer} onChange={(event) => setIssuer(event.target.value)} required aria-invalid={Boolean(errors.issuer)} /></Field>
              <Field label={t('subject')} error={errors.subject}><AntInput value={subject} onChange={(event) => setSubject(event.target.value)} required aria-invalid={Boolean(errors.subject)} /></Field>
              <Field label={t('name')} error={errors.name}><AntInput value={name} onChange={(event) => setName(event.target.value)} required aria-invalid={Boolean(errors.name)} /></Field>
              <Field label={t('email')} error={errors.email}><AntInput type="email" value={email} onChange={(event) => setEmail(event.target.value)} aria-invalid={Boolean(errors.email)} /></Field>
            </div>
            <div className="ops-form-actions">
              <AntActionButton tone="ghost" className="ops-button ops-button-secondary" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</AntActionButton>
              <AntActionButton tone="primary" className="ops-button" type="submit" disabled={submitting || (touched && invalid)}>{submitting ? t('provisioning') : t('provision')}</AntActionButton>
            </div>
          </form>
        </Dialog>
      ) : null}
    </section>
  )
}
