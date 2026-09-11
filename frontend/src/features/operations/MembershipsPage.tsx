import { Plus } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useParams } from 'react-router-dom'
import { operationsApi } from './api'
import { Dialog, EmptyState, ErrorState, Field, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import type { ProjectRole } from './types'
import { apiErrorMessage, formatDate, toOffsetDateTime } from './utils'
import { useRemoteData } from './useRemoteData'

const roles: ProjectRole[] = ['PROJE_YONETICISI', 'GELISTIRICI', 'OPERASYON', 'YAYIN_ONAYLAYICI', 'GORUNTULEYICI']

export function MembershipsPage() {
  const { projectUuid = '' } = useParams()
  const { t, locale } = useOperationsI18n()
  const memberships = useRemoteData(() => operationsApi.listMemberships(projectUuid), [projectUuid])
  const users = useRemoteData(() => operationsApi.listUsers(), [])
  const userNames = useMemo(() => new Map(users.data?.map((user) => [user.uuid, user.name]) ?? []), [users.data])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [userUuid, setUserUuid] = useState('')
  const [role, setRole] = useState<ProjectRole>('GELISTIRICI')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  const periodInvalid = Boolean(startsAt && endsAt && new Date(endsAt) <= new Date(startsAt))
  const userError = touched && !userUuid ? t('required') : ''

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!userUuid || periodInvalid) return
    setSubmitting(true)
    setSubmitError('')
    try {
      await operationsApi.createMembership(projectUuid, {
        userUuid: userUuid.trim(),
        role,
        startsAt: toOffsetDateTime(startsAt),
        endsAt: toOffsetDateTime(endsAt),
      })
      setUserUuid('')
      setRole('GELISTIRICI')
      setStartsAt('')
      setEndsAt('')
      setTouched(false)
      setDialogOpen(false)
      await memberships.reload()
    } catch (error) {
      setSubmitError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="ops-page">
      <PageHeader
        title={t('memberships')}
        description={t('membershipsHelp')}
        actions={<button className="ops-button" type="button" onClick={() => setDialogOpen(true)}><Plus aria-hidden="true" /> {t('newMembership')}</button>}
      />
      <Panel>
        {memberships.loading ? <LoadingState /> : null}
        {!memberships.loading && memberships.error ? <ErrorState message={apiErrorMessage(memberships.error, t('requestFailed'))} onRetry={() => void memberships.reload()} /> : null}
        {!memberships.loading && !memberships.error && memberships.data?.length === 0 ? <EmptyState>{t('emptyMemberships')}</EmptyState> : null}
        {!memberships.loading && memberships.data && memberships.data.length > 0 ? (
          <div className="ops-table-wrap">
            <table className="ops-table">
              <thead><tr><th scope="col">{t('user')}</th><th scope="col">{t('roles')}</th><th scope="col">{t('status')}</th><th scope="col">{t('activePeriod')}</th></tr></thead>
              <tbody>
                {memberships.data.map((membership) => (
                  <tr key={membership.uuid}>
                    <td>{userNames.get(membership.userUuid) ?? t('unknownUser')}</td>
                    <td>{membership.roles.map((item) => t(`role_${item.code}` as Parameters<typeof t>[0])).join(', ')}</td>
                    <td><StatusBadge value={membership.status} /></td>
                    <td>{formatDate(membership.startsAt, locale, t('immediately'))}<span className="ops-cell-secondary">{formatDate(membership.endsAt, locale, t('never'))}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Panel>

      {dialogOpen ? (
        <Dialog title={t('newMembership')} onClose={() => !submitting && setDialogOpen(false)}>
          <form className="ops-form" onSubmit={(event) => void submit(event)} noValidate>
            {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
            <Field label={t('user')} error={userError}>
              <select value={userUuid} onChange={(event) => setUserUuid(event.target.value)} required aria-invalid={Boolean(userError)} disabled={users.loading || !users.data?.length}>
                <option value="">—</option>
                {users.data?.map((user) => <option key={user.uuid} value={user.uuid}>{user.name}{user.email ? ` · ${user.email}` : ''}</option>)}
              </select>
            </Field>
            <Field label={t('role')}>
              <select value={role} onChange={(event) => setRole(event.target.value as ProjectRole)}>
                {roles.map((item) => <option key={item} value={item}>{t(`role_${item}` as Parameters<typeof t>[0])}</option>)}
              </select>
            </Field>
            <div className="ops-form-grid">
              <Field label={t('startsAt')}><input type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></Field>
              <Field label={t('endsAt')} error={periodInvalid ? t('invalidPeriod') : undefined}><input type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} aria-invalid={periodInvalid} /></Field>
            </div>
            <div className="ops-form-actions">
              <button className="ops-button ops-button-secondary" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</button>
              <button className="ops-button" type="submit" disabled={submitting || periodInvalid}>{submitting ? t('adding') : t('addMembership')}</button>
            </div>
          </form>
        </Dialog>
      ) : null}
    </section>
  )
}
