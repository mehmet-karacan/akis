import { Input as AntInput, Tag } from 'antd'
import { BadgeCheck, CircleAlert, Hash, KeyRound, Mail, Plus, ShieldCheck, UserRound } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AsyncState, Button, Dialog, ExportMenu, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
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
const fullName = (user: IdentityUser) => `${user.ad}${user.soyad ? ` ${user.soyad}` : ''}`

export function IdentityUsersPage() {
  const { t, locale } = useOperationsI18n()
  const tr = locale.startsWith('tr')
  const [view, setView] = useCollectionView('akis:identity-users:view')
  const [params, setParams] = useSearchParams()
  const users = useRemoteData(() => operationsApi.listUsers(), [])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [selected, setSelected] = useState<IdentityUser | null>(null)
  const [userCode, setUserCode] = useState('')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [employeeNumber, setEmployeeNumber] = useState('')
  const [email, setEmail] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [setupToken, setSetupToken] = useState<{ token: string; expiresAt: string } | null>(null)
  const [issuingToken, setIssuingToken] = useState(false)
  const [tokenError, setTokenError] = useState('')

  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})
  const items = useMemo(() => (users.data ?? [])
    .filter((user) => `${user.kullaniciKodu} ${fullName(user)} ${user.sicilNumarasi ?? ''} ${user.eposta ?? ''}`.toLocaleLowerCase(locale).includes(query.trim().toLocaleLowerCase(locale)))
    .sort((a, b) => fullName(a).localeCompare(fullName(b), locale)), [users.data, query, locale])

  const errors = {
    userCode: touched && !userCode.trim() ? t('required') : '',
    firstName: touched && !firstName.trim() ? t('required') : '',
    email: touched && email.trim() && !EMAIL.test(email.trim()) ? t('invalidEmail') : '',
  }
  const invalid = Object.values(errors).some(Boolean)

  const submit = async (event: FormEvent) => {
    event.preventDefault(); setTouched(true)
    if (!userCode.trim() || !firstName.trim() || (email.trim() && !EMAIL.test(email.trim()))) return
    setSubmitting(true); setSubmitError('')
    try {
      await operationsApi.createUser({ kullaniciKodu: userCode.trim(), ad: firstName.trim(), soyad: lastName.trim() || null, sicilNumarasi: employeeNumber.trim() || null, eposta: email.trim() || null })
      setUserCode(''); setFirstName(''); setLastName(''); setEmployeeNumber(''); setEmail(''); setTouched(false); setDialogOpen(false)
      await users.reload()
    } catch (error) { setSubmitError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setSubmitting(false) }
  }

  const active = (user: IdentityUser) => user.status === 'AKTIF'
  const issueSetupToken = async (user: IdentityUser) => {
    setIssuingToken(true); setTokenError(''); setSetupToken(null)
    try { setSetupToken(await operationsApi.issuePasswordSetupToken(user.uuid)) }
    catch (error) { setTokenError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setIssuingToken(false) }
  }
  const statusText = (user: IdentityUser) => user.status === 'PAROLA_BEKLIYOR' ? (tr ? 'Parola bekliyor' : 'Password pending') : user.status === 'PASIF' ? (tr ? 'Pasif' : 'Inactive') : (tr ? 'Aktif' : 'Active')
  const addButton = <Button tone="primary" icon={<Plus size={16} />} onClick={() => setDialogOpen(true)}>{t('newUser')}</Button>
  const all = users.data ?? []
  const notRecorded = tr ? 'Kaydedilmemiş' : 'Not recorded'

  return <section className="page-stack connections-page identity-users-page">
    <section className="connection-management-panel"><PageHeader icon={<UserRound />} eyebrow={tr ? 'KULLANICI YÖNETİMİ' : 'USER MANAGEMENT'} title={tr ? 'Kullanıcılar' : 'Users'} description={tr ? 'Uygulama kullanıcılarını, kurumsal bilgilerini ve hesap durumlarını yönetin.' : 'Manage application users, corporate details and account states.'} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Kullanıcı kodu, ad, sicil veya e-posta ara' : 'Search user code, name, employee number or email'} /></section>
    <SummaryStrip ariaLabel={tr ? 'Kullanıcı özeti' : 'User summary'} items={[
      { label: tr ? 'Toplam Kullanıcı' : 'Total Users', value: all.length, icon: <UserRound />, tone: 'info' },
      { label: tr ? 'Aktif' : 'Active', value: all.filter(active).length, icon: <ShieldCheck />, tone: 'success' },
      { label: tr ? 'Parola Bekleyen' : 'Password Pending', value: all.filter((user) => user.status === 'PAROLA_BEKLIYOR').length, icon: <BadgeCheck />, tone: 'warning' },
      { label: tr ? 'Sicil Tanımlı' : 'Employee Number Set', value: all.filter((user) => user.sicilNumarasi).length, icon: <Hash />, tone: 'teal' },
    ]} />
    <section className="connections-records">
    {users.loading ? <AsyncState state="loading" title={t('loading')} /> : users.error ? <AsyncState state="error" title={apiErrorMessage(users.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void users.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={tr ? 'Henüz kullanıcı tanımlanmadı.' : 'No users defined yet.'} action={addButton} /> : <ProgressiveRecords key={query} items={items}>{(visible) => <DataGrid collectionTitle={tr ? 'Kullanıcı Kataloğu' : 'User Catalog'} collectionIcon={<UserRound />} toolbarActions={<><ExportMenu globalScope dataset="identity" resourceId="users" filters={query ? [{ field: 'query', operator: 'contains', value: query }] : []} label={tr ? 'Dışa Aktar' : 'Export'} />{addButton}</>} cardHeaderField="status" cardHiddenFields={['status']} view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="name">{tr ? 'Kullanıcı' : 'User'}</th><th data-field-key="userCode">{tr ? 'Kısa Kod' : 'Short Code'}</th><th data-field-key="employeeNumber">{tr ? 'Sicil Numarası' : 'Employee Number'}</th><th data-field-key="email">{t('email')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="createdAt">{t('createdAt')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{visible.map((user) => { const ok = active(user); const tone = ok ? 'success' : 'warning'; return <tr key={user.uuid}>
        <td><span className="connection-record-identity"><strong>{fullName(user)}</strong><small>{user.eposta || notRecorded}</small></span></td>
        <td><code>{user.kullaniciKodu}</code></td><td>{user.sicilNumarasi || notRecorded}</td><td>{user.eposta || notRecorded}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <ShieldCheck size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{statusText(user)}</span></Tag></td>
        <td>{formatDate(user.createdAt, locale)}</td><td className="row-actions"><RecordActionButton name={fullName(user)} editable={false} onClick={() => setSelected(user)} /></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>

    {selected && <RecordDetailDialog open title={<span className="connection-dialog-title"><UserRound size={17} />{fullName(selected)}</span>} readOnly onClose={() => { setSelected(null); setSetupToken(null); setTokenError('') }} className="connection-catalog-dialog">
      <section className="connection-detail-section"><div className="form-grid two-column">
        <label>{tr ? 'Kısa Kod' : 'Short Code'}<AntInput value={selected.kullaniciKodu} readOnly /></label><label>{t('status')}<AntInput value={statusText(selected)} readOnly /></label>
        <label>{tr ? 'Ad' : 'First Name'}<AntInput value={selected.ad} readOnly /></label><label>{tr ? 'Soyad' : 'Last Name'}<AntInput value={selected.soyad ?? ''} readOnly /></label>
        <label>{tr ? 'Sicil Numarası' : 'Employee Number'}<AntInput value={selected.sicilNumarasi ?? ''} readOnly /></label><label>{t('email')}<AntInput value={selected.eposta ?? ''} readOnly /></label>
        <label>{t('createdAt')}<AntInput value={formatDate(selected.createdAt, locale)} readOnly /></label>
      </div><p className="form-note">{tr ? 'Yeni hesaplar güvenli parola kurulum süreci tamamlanana kadar Parola bekliyor durumunda kalır.' : 'New accounts remain Password pending until secure password setup is completed.'}</p>
      <div className="topology-form-actions"><Button tone="secondary" icon={<KeyRound size={16} />} busy={issuingToken} onClick={() => void issueSetupToken(selected)}>{tr ? 'Tek Kullanımlık Kod Üret' : 'Issue One-time Token'}</Button></div>
      {tokenError && <div className="topology-inline-error" role="alert"><CircleAlert />{tokenError}</div>}
      {setupToken && <div className="form-grid two-column" role="status">
        <label>{tr ? 'Kurulum Kodu (yalnız bu kez gösterilir)' : 'Setup Token (shown once)'}<AntInput.TextArea value={setupToken.token} readOnly autoSize /></label>
        <label>{tr ? 'Geçerlilik Sonu' : 'Expires At'}<AntInput value={formatDate(setupToken.expiresAt, locale)} readOnly /></label>
      </div>}
      </section>
    </RecordDetailDialog>}

    <Dialog open={dialogOpen} title={t('newUser')} closeLabel={t('close')} busy={submitting} onClose={() => !submitting && setDialogOpen(false)} className="connection-catalog-dialog">
      <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void submit(event)} noValidate>
        {submitError ? <div className="topology-inline-error" role="alert"><CircleAlert />{submitError}</div> : null}
        <div className="topology-form topology-form--grid">
          <Field label={tr ? 'Kısa Kod' : 'Short Code'} error={errors.userCode}><AntInput value={userCode} onChange={(event) => setUserCode(event.target.value)} required /></Field>
          <Field label={tr ? 'Ad' : 'First Name'} error={errors.firstName}><AntInput value={firstName} onChange={(event) => setFirstName(event.target.value)} required /></Field>
          <Field label={tr ? 'Soyad' : 'Last Name'}><AntInput value={lastName} onChange={(event) => setLastName(event.target.value)} /></Field>
          <Field label={tr ? 'Sicil Numarası' : 'Employee Number'}><AntInput value={employeeNumber} onChange={(event) => setEmployeeNumber(event.target.value)} /></Field>
          <Field label={t('email')} error={errors.email}><AntInput prefix={<Mail size={14} />} type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></Field>
        </div>
        <footer className="topology-form-actions"><Button tone="ghost" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</Button><Button tone="primary" type="submit" busy={submitting} disabled={touched && invalid}>{submitting ? t('provisioning') : t('provision')}</Button></footer>
      </form>
    </Dialog>
  </section>
}
