import { Input as AntInput, Tag } from 'antd'
import { CalendarClock, CheckCircle2, CircleAlert, Plus, ShieldCheck, UserRound, Users } from 'lucide-react'
import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { Select as FormSelect } from '../../core/ui/Select'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { operationsApi } from './api'
import { Field } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import type { Membership, ProjectRole } from './types'
import { apiErrorMessage, formatDate, toOffsetDateTime } from './utils'
import { useRemoteData } from './useRemoteData'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import '../topology/topology.css'

const roles: ProjectRole[] = ['PROJE_YONETICISI', 'GELISTIRICI', 'OPERASYON', 'YAYIN_ONAYLAYICI', 'GORUNTULEYICI']

/** Project memberships in the shared catalog layout: header + filter, summary strip, card/list/table records. */
export function MembershipsPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, locale } = useOperationsI18n()
  const tr = locale.startsWith('tr')
  const [view, setView] = useCollectionView('akis:memberships:view')
  const [params, setParams] = useSearchParams()
  const memberships = useRemoteData(() => operationsApi.listMemberships(projectUuid), [projectUuid])
  const users = useRemoteData(() => operationsApi.listUsers(), [])
  const userOf = useCallback((uuid: string) => users.data?.find((user) => user.uuid === uuid), [users.data])
  const roleLabel = useCallback((code: string) => t(`role_${code}` as Parameters<typeof t>[0]), [t])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [selected, setSelected] = useState<Membership | null>(null)
  const [userUuid, setUserUuid] = useState('')
  const [role, setRole] = useState<ProjectRole>('GELISTIRICI')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})
  const items = useMemo(() => (memberships.data ?? [])
    .filter((membership) => { const user = userOf(membership.userUuid); return `${user?.name ?? ''} ${user?.email ?? ''} ${membership.roles.map((item) => roleLabel(item.code)).join(' ')}`.toLocaleLowerCase(locale).includes(query.trim().toLocaleLowerCase(locale)) })
    .sort((a, b) => (userOf(a.userUuid)?.name ?? '').localeCompare(userOf(b.userUuid)?.name ?? '', locale)), [memberships.data, userOf, roleLabel, query, locale])

  const periodInvalid = Boolean(startsAt && endsAt && new Date(endsAt) <= new Date(startsAt))
  const userError = touched && !userUuid ? t('required') : ''

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!userUuid || periodInvalid) return
    setSubmitting(true); setSubmitError('')
    try {
      await operationsApi.createMembership(projectUuid, { userUuid: userUuid.trim(), role, startsAt: toOffsetDateTime(startsAt), endsAt: toOffsetDateTime(endsAt) })
      setUserUuid(''); setRole('GELISTIRICI'); setStartsAt(''); setEndsAt(''); setTouched(false); setDialogOpen(false)
      await memberships.reload()
    } catch (error) { setSubmitError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setSubmitting(false) }
  }

  const active = (membership: Membership) => ['AKTIF', 'ETKIN'].includes(membership.status)
  const statusText = (membership: Membership) => { const key = `status_${membership.status}` as Parameters<typeof t>[0]; return ['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(membership.status) ? t(key) : membership.status }
  const addButton = <Button tone="primary" icon={<Plus size={16} />} onClick={() => setDialogOpen(true)}>{t('newMembership')}</Button>
  const all = memberships.data ?? []

  return <section className="page-stack connections-page memberships-page">
    <section className="connection-management-panel"><PageHeader icon={<Users />} eyebrow={tr ? 'EKİP' : 'TEAM'} title={t('memberships')} description={t('membershipsHelp')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Kullanıcı, e-posta veya role göre ara' : 'Search by user, email or role'} /></section>
    <SummaryStrip ariaLabel={t('memberships')} items={[
      { label: tr ? 'Toplam Üyelik' : 'Total Memberships', value: all.length, icon: <Users />, tone: 'info' },
      { label: tr ? 'Etkin Üyelik' : 'Active Memberships', value: all.filter(active).length, icon: <ShieldCheck />, tone: 'success' },
      { label: tr ? 'Süreli Üyelik' : 'Time-boxed', value: all.filter((membership) => membership.endsAt).length, icon: <CalendarClock />, tone: 'warning' },
      { label: t('users'), value: users.data?.length ?? 0, icon: <UserRound />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
    {memberships.loading ? <AsyncState state="loading" title={t('loading')} /> : memberships.error ? <AsyncState state="error" title={apiErrorMessage(memberships.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void memberships.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={t('emptyMemberships')} action={addButton} /> : <ProgressiveRecords key={query} items={items}>{(visible) => <DataGrid collectionTitle={tr ? 'Üyelik Kataloğu' : 'Membership Catalog'} collectionIcon={<Users />} toolbarActions={addButton} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="name">{t('user')}</th><th data-field-key="email">{t('email')}</th><th data-field-key="roles">{t('roles')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="startsAt">{t('startsAt')}</th><th data-field-key="endsAt">{t('endsAt')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{visible.map((membership) => { const user = userOf(membership.userUuid); const ok = active(membership); const tone = ok ? 'success' : 'warning'; return <tr key={membership.uuid} data-connection-uuid={membership.uuid}>
        <td><span className="connection-record-identity"><strong>{user?.name ?? t('unknownUser')}</strong><small>{user?.subject ?? membership.userUuid}</small></span></td>
        <td>{user?.email || (tr ? 'Kaydedilmemiş' : 'Not recorded')}</td>
        <td>{membership.roles.map((item) => roleLabel(item.code)).join(', ')}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{statusText(membership)}</span></Tag></td>
        <td>{formatDate(membership.startsAt, locale, t('immediately'))}</td>
        <td>{formatDate(membership.endsAt, locale, t('never'))}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={user?.name ?? membership.uuid} editable={false} onClick={() => setSelected(membership)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>

    {selected && <RecordDetailDialog open title={<span className="connection-dialog-title"><Users size={17} aria-hidden="true" />{userOf(selected.userUuid)?.name ?? t('unknownUser')}</span>} readOnly onClose={() => setSelected(null)} className="connection-catalog-dialog">
      <section className="connection-detail-section"><div className="form-grid two-column">
        <label>{t('user')}<AntInput value={userOf(selected.userUuid)?.name ?? t('unknownUser')} readOnly /></label>
        <label>{t('email')}<AntInput value={userOf(selected.userUuid)?.email ?? ''} readOnly /></label>
        <label>{t('roles')}<AntInput value={selected.roles.map((item) => roleLabel(item.code)).join(', ')} readOnly /></label>
        <label>{t('status')}<AntInput value={statusText(selected)} readOnly /></label>
        <label>{t('startsAt')}<AntInput value={formatDate(selected.startsAt, locale, t('immediately'))} readOnly /></label>
        <label>{t('endsAt')}<AntInput value={formatDate(selected.endsAt, locale, t('never'))} readOnly /></label>
      </div><p className="form-note">{tr ? 'Üyelikler değiştirilemez; rol veya süre değişikliği için yeni üyelik ekleyin.' : 'Memberships are immutable; add a new membership to change the role or period.'}</p></section>
    </RecordDetailDialog>}

    <Dialog open={dialogOpen} title={t('newMembership')} closeLabel={t('close')} busy={submitting} onClose={() => !submitting && setDialogOpen(false)} className="connection-catalog-dialog">
      <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void submit(event)} noValidate>
        {submitError ? <div className="topology-inline-error" role="alert"><CircleAlert />{submitError}</div> : null}
        <div className="topology-form topology-form--grid">
          <Field label={t('user')} error={userError}>
            <FormSelect value={userUuid} onChange={(event) => setUserUuid(event.target.value)} required aria-invalid={Boolean(userError)} disabled={users.loading || !users.data?.length}>
              <option value="">{t('selectUser')}</option>
              {users.data?.map((user) => <option key={user.uuid} value={user.uuid}>{user.name}{user.email ? ` · ${user.email}` : ''}</option>)}
            </FormSelect>
          </Field>
          <Field label={t('role')}>
            <FormSelect value={role} onChange={(event) => setRole(event.target.value as ProjectRole)}>
              {roles.map((item) => <option key={item} value={item}>{roleLabel(item)}</option>)}
            </FormSelect>
          </Field>
          <Field label={t('startsAt')}><AntInput type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></Field>
          <Field label={t('endsAt')} error={periodInvalid ? t('invalidPeriod') : undefined}><AntInput type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} aria-invalid={periodInvalid} /></Field>
        </div>
        <footer className="topology-form-actions">
          <Button tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</Button>
          <Button tone="primary" className="topology-button" type="submit" busy={submitting} disabled={periodInvalid}>{submitting ? t('adding') : t('addMembership')}</Button>
        </footer>
      </form>
    </Dialog>
  </section>
}
