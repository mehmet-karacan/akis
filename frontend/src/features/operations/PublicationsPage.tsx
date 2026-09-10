import { Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { operationsApi } from './api'
import { Dialog, EmptyState, ErrorState, Field, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import { apiErrorMessage, formatDate, isUuid } from './utils'
import { useRemoteData } from './useRemoteData'

export function PublicationsPage() {
  const { projectUuid = '' } = useParams()
  const { t, locale } = useOperationsI18n()
  const publications = useRemoteData(
    () => operationsApi.listPublications(projectUuid),
    [projectUuid],
  )
  const [dialogOpen, setDialogOpen] = useState(false)
  const [scenarioUuid, setScenarioUuid] = useState('')
  const [environmentUuid, setEnvironmentUuid] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [touched, setTouched] = useState(false)

  const scenarioError = touched && !isUuid(scenarioUuid) ? t('invalidUuid') : ''
  const environmentError = touched && !isUuid(environmentUuid) ? t('invalidUuid') : ''

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setTouched(true)
    if (!isUuid(scenarioUuid) || !isUuid(environmentUuid)) return
    setSubmitting(true)
    setSubmitError('')
    try {
      await operationsApi.createPublication(projectUuid, scenarioUuid.trim(), environmentUuid.trim())
      setDialogOpen(false)
      setScenarioUuid('')
      setEnvironmentUuid('')
      setTouched(false)
      await publications.reload()
    } catch (error) {
      setSubmitError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="ops-page">
      <PageHeader
        title={t('publications')}
        description={t('publicationsHelp')}
        actions={(
          <button className="ops-button" type="button" onClick={() => setDialogOpen(true)}>
            <Plus aria-hidden="true" /> {t('newPublication')}
          </button>
        )}
      />

      <Panel>
        {publications.loading ? <LoadingState /> : null}
        {!publications.loading && publications.error ? (
          <ErrorState
            message={apiErrorMessage(publications.error, t('requestFailed'))}
            onRetry={() => void publications.reload()}
          />
        ) : null}
        {!publications.loading && !publications.error && publications.data?.length === 0 ? (
          <EmptyState>{t('emptyPublications')}</EmptyState>
        ) : null}
        {!publications.loading && publications.data && publications.data.length > 0 ? (
          <div className="ops-table-wrap">
            <table className="ops-table">
              <thead>
                <tr>
                  <th scope="col">{t('number')}</th>
                  <th scope="col">{t('environment')}</th>
                  <th scope="col">{t('status')}</th>
                  <th scope="col">{t('releaseHash')}</th>
                  <th scope="col">{t('createdAt')}</th>
                  <th scope="col" className="ops-actions-column">{t('actions')}</th>
                </tr>
              </thead>
              <tbody>
                {publications.data.map((publication) => (
                  <tr key={publication.uuid}>
                    <td>#{publication.publicationNumber}</td>
                    <td>
                      {publication.environmentCode}
                      <span className="ops-cell-secondary">{publication.environmentRisk}</span>
                    </td>
                    <td><StatusBadge value={publication.status} /></td>
                    <td><code title={publication.releaseHash}>{publication.releaseHash.slice(0, 12)}…</code></td>
                    <td>{formatDate(publication.createdAt, locale)}</td>
                    <td>
                      <Link
                        className="ops-link"
                        to={`/projects/${encodeURIComponent(projectUuid)}/publications/${publication.uuid}`}
                      >
                        {t('viewDetails')}
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Panel>

      {dialogOpen ? (
        <Dialog title={t('newPublication')} onClose={() => !submitting && setDialogOpen(false)}>
          <form className="ops-form" onSubmit={(event) => void submit(event)} noValidate>
            {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
            <Field label={t('scenarioUuid')} error={scenarioError}>
              <input
                value={scenarioUuid}
                onChange={(event) => setScenarioUuid(event.target.value)}
                autoComplete="off"
                required
                aria-invalid={Boolean(scenarioError)}
              />
            </Field>
            <Field label={t('environmentUuid')} error={environmentError}>
              <input
                value={environmentUuid}
                onChange={(event) => setEnvironmentUuid(event.target.value)}
                autoComplete="off"
                required
                aria-invalid={Boolean(environmentError)}
              />
            </Field>
            <div className="ops-form-actions">
              <button className="ops-button ops-button-secondary" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</button>
              <button className="ops-button" type="submit" disabled={submitting}>{submitting ? t('creating') : t('create')}</button>
            </div>
          </form>
        </Dialog>
      ) : null}
    </main>
  )
}
