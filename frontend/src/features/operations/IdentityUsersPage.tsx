import { Input as AntInput, Tag } from 'antd'
import { CheckCircle2, CircleAlert, KeyRound, Mail, Plus, ShieldCheck, UserRound } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AsyncState, Button, Dialog, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { operationsApi } from './api'
import { Field } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import type { IdentityUser } from './types'
import { apiErrorMessage, formatDate } from './utils'
import { useRemoteData } from './useRemoteData'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import '../topology/topology.css'

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** OIDC identities in the shared catalog layout: header + filter, summary strip, card/list/table records. */
export function IdentityUsersPage() {
  const { t, locale } = useOperationsI18n()
  const tr = locale.startsWith('tr')
  const [view, setView] = useCollectionView('akis:identity-users:view')
  const [params, setParams] = useSearchParams()
  const users = useRemoteData(() => operationsApi.listUsers(), [])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [selected, setSelected] = useState<IdentityUser | null>(null)
  const [issuer, setIssuer] = useState('')
  const [subject, setSubject] = useState('')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})
  const items = useMemo(() => (users.data ?? [])
    .filter((user) => `${user.name} ${user.email ?? ''} ${user.issuer} ${user.subject}`.toLocaleLowerCase(locale).includes(query.trim().toLocaleLowerCase(locale)))
    .sort((a, b) => a.name.localeCompare(b.name, locale)), [users.data, query, locale])

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
    setSubmitting(true); setSubmitError('')
    try {
      await operationsApi.createUser({ issuer: issuer.trim(), subject: subject.trim(), name: name.trim(), email: email.trim() || null })
      setIssuer(''); setSubject(''); setName(''); setEmail(''); setTouched(false); setDialogOpen(false)
      await users.reload()
    } catch (error) { setSubmitError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setSubmitting(false) }
  }

  const active = (user: IdentityUser) => ['AKTIF', 'ETKIN'].includes(user.status)
  const statusText = (user: IdentityUser) => { const key = `status_${user.status}` as Parameters<typeof t>[0]; return ['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(user.status) ? t(key) : user.status }
  const addButton = <Button tone="primary" icon={<Plus size={16} />} onClick={() => setDialogOpen(true)}>{t('newUser')}</Button>
  const all = users.data ?? []
  const notRecorded = tr ? 'Kaydedilmemiş' : 'Not recorded'

  return <section className="page-stack connections-page identity-users-page">
    <section className="connection-management-panel"><PageHeader icon={<UserRound />} eyebrow={tr ? 'KİMLİK' : 'IDENTITY'} title={t('users')} description={t('usersHelp')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Ad, e-posta, sağlayıcı veya konuya göre ara' : 'Search by name, email, issuer or subject'} /></section>
    <SummaryStrip ariaLabel={t('users')} items={[
      { label: tr ? 'Toplam Kullanıcı' : 'Total Users', value: all.length, icon: <UserRound />, tone: 'info' },
      { label: tr ? 'Etkin Kullanıcı' : 'Active Users', value: all.filter(active).length, icon: <ShieldCheck />, tone: 'success' },
      { label: tr ? 'E-postalı' : 'With Email', value: all.filter((user) => user.email).length, icon: <Mail />, tone: 'neutral' },
      { label: tr ? 'Kimlik Sağlayıcı' : 'Issuers', value: new Set(all.map((user) => user.issuer)).size, icon: <KeyRound />, tone: 'teal' },
    ]} />
    <section className="connections-records">
    {users.loading ? <AsyncState state="loading" title={t('loading')} /> : users.error ? <AsyncState state="error" title={apiErrorMessage(users.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void users.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={t('emptyUsers')} action={addButton} /> : <ProgressiveRecords key={query} items={items}>{(visible) => <DataGrid collectionTitle={tr ? 'Kullanıcı Kataloğu' : 'User Catalog'} collectionIcon={<UserRound />} toolbarActions={addButton} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="name">{t('name')}</th><th data-field-key="email">{t('email')}</th><th data-field-key="issuer">{t('issuer')}</th><th data-field-key="subject">{t('subject')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="createdAt">{t('createdAt')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{visible.map((user) => { const ok = active(user); const tone = ok ? 'success' : 'warning'; return <tr key={user.uuid} data-connection-uuid={user.uuid}>
        <td><span className="connection-record-identity"><strong>{user.name}</strong><small>{user.email || notRecorded}</small></span></td>
        <td>{user.email || notRecorded}</td>
        <td>{user.issuer}</td>
        <td><code title={user.subject}>{user.subject}</code></td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{statusText(user)}</span></Tag></td>
        <td>{formatDate(user.createdAt, locale)}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={user.name} editable={false} onClick={() => setSelected(user)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>

    {selected && <RecordDetailDialog open title={<span className="connection-dialog-title"><UserRound size={17} aria-hidden="true" />{selected.name}</span>} readOnly onClose={() => setSelected(null)} className="connection-catalog-dialog">
      <section className="connection-detail-section"><div className="form-grid two-column">
        <label>{t('name')}<AntInput value={selected.name} readOnly /></label>
        <label>{t('email')}<AntInput value={selected.email ?? ''} readOnly /></label>
        <label>{t('issuer')}<AntInput value={selected.issuer} readOnly /></label>
        <label>{t('subject')}<AntInput value={selected.subject} readOnly /></label>
        <label>{t('status')}<AntInput value={statusText(selected)} readOnly /></label>
        <label>{t('createdAt')}<AntInput value={formatDate(selected.createdAt, locale)} readOnly /></label>
      </div><p className="form-note">{tr ? 'Kimlik kayıtları OIDC sağlayıcısından gelir; burada yalnız tanımlanır ve görüntülenir.' : 'Identities originate from the OIDC provider; they are only provisioned and viewed here.'}</p></section>
    </RecordDetailDialog>}

    <Dialog open={dialogOpen} title={t('newUser')} closeLabel={t('close')} busy={submitting} onClose={() => !submitting && setDialogOpen(false)} className="connection-catalog-dialog">
      <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void submit(event)} noValidate>
        {submitError ? <div className="topology-inline-error" role="alert"><CircleAlert />{submitError}</div> : null}
        <div className="topology-form topology-form--grid">
          <Field label={t('issuer')} error={errors.issuer}><AntInput value={issuer} onChange={(event) => setIssuer(event.target.value)} required aria-invalid={Boolean(errors.issuer)} /></Field>
          <Field label={t('subject')} error={errors.subject}><AntInput value={subject} onChange={(event) => setSubject(event.target.value)} required aria-invalid={Boolean(errors.subject)} /></Field>
          <Field label={t('name')} error={errors.name}><AntInput value={name} onChange={(event) => setName(event.target.value)} required aria-invalid={Boolean(errors.name)} /></Field>
          <Field label={t('email')} error={errors.email}><AntInput type="email" value={email} onChange={(event) => setEmail(event.target.value)} aria-invalid={Boolean(errors.email)} /></Field>
        </div>
        <footer className="topology-form-actions">
          <Button tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</Button>
          <Button tone="primary" className="topology-button" type="submit" busy={submitting} disabled={touched && invalid}>{submitting ? t('provisioning') : t('provision')}</Button>
        </footer>
      </form>
    </Dialog>
  </section>
}
