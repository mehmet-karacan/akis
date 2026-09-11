import { Play, Plus } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { operationsApi } from '../operations/api'
import type { Publication } from '../operations/types'
import { Dialog, EmptyState, ErrorState, Field, LoadingState, PageHeader, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { createIdempotencyKey, executionApi, isExecutionDisabled } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import './execution.css'

export function RunsPage() {
  const { projectUuid = '' } = useParams()
  const navigate = useNavigate()
  const { t, locale } = useExecutionI18n()
  const runs = useRemoteData(() => executionApi.listRuns(projectUuid), [projectUuid])
  const publications = useRemoteData(
    () => operationsApi.listPublications(projectUuid),
    [projectUuid],
  )
  const activePublications = useMemo(
    () => (publications.data ?? []).filter((publication) => publication.status === 'AKTIF'),
    [publications.data],
  )
  const [dialogOpen, setDialogOpen] = useState(false)
  const [publicationUuid, setPublicationUuid] = useState('')
  const [idempotencyKey, setIdempotencyKey] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [executionDisabled, setExecutionDisabled] = useState(false)

  const openDialog = () => {
    const firstPublication = activePublications[0]
    setPublicationUuid(firstPublication?.uuid ?? '')
    setIdempotencyKey(createIdempotencyKey())
    setSubmitError('')
    setDialogOpen(true)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!publicationUuid || !idempotencyKey) return
    setSubmitting(true)
    setSubmitError('')
    try {
      const run = await executionApi.startRun(projectUuid, publicationUuid, idempotencyKey)
      setDialogOpen(false)
      await navigate(`/projects/${encodeURIComponent(projectUuid)}/runs/${run.runUuid}`)
    } catch (error) {
      if (isExecutionDisabled(error)) {
        setExecutionDisabled(true)
        setDialogOpen(false)
      } else {
        setSubmitError(apiErrorMessage(error, t('requestFailed')))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const publicationLabel = (publication: Publication) =>
    `#${publication.publicationNumber} · ${publication.environmentCode}`

  return (
    <main className="ops-page execution-page">
      <PageHeader
        title={t('runs')}
        description={t('runsHelp')}
        actions={(
          <button
            className="ops-button"
            type="button"
            onClick={openDialog}
            disabled={executionDisabled || publications.loading || activePublications.length === 0}
          >
            <Plus aria-hidden="true" /> {t('startRun')}
          </button>
        )}
      />

      {executionDisabled ? <ExecutionDisabledNotice /> : null}
      {!publications.loading && publications.error ? (
        <ErrorState
          message={apiErrorMessage(publications.error, t('requestFailed'))}
          onRetry={() => void publications.reload()}
        />
      ) : null}
      {!publications.loading && !publications.error && activePublications.length === 0 ? (
        <div className="execution-guidance"><Play aria-hidden="true" /><span>{t('noActivePublication')}</span></div>
      ) : null}

      <Panel>
        {runs.loading ? <LoadingState /> : null}
        {!runs.loading && runs.error ? (
          <ErrorState message={apiErrorMessage(runs.error, t('requestFailed'))} onRetry={() => void runs.reload()} />
        ) : null}
        {!runs.loading && !runs.error && runs.data?.length === 0 ? <EmptyState>{t('emptyRuns')}</EmptyState> : null}
        {!runs.loading && runs.data && runs.data.length > 0 ? (
          <div className="ops-table-wrap">
            <table className="ops-table">
              <thead><tr>
                <th scope="col">{t('runUuid')}</th><th scope="col">{t('publication')}</th>
                <th scope="col">{t('attempt')}</th><th scope="col">{t('startType')}</th>
                <th scope="col">{t('status')}</th><th scope="col">{t('createdAt')}</th>
                <th scope="col">{t('actions')}</th>
              </tr></thead>
              <tbody>{runs.data.map((run) => (
                <tr key={run.runUuid}>
                  <td><code title={run.runUuid}>{run.runUuid.slice(0, 8)}…</code></td>
                  <td><code title={run.publicationUuid}>{run.publicationUuid.slice(0, 8)}…</code></td>
                  <td>#{run.attemptNumber}</td><td>{run.startType}</td>
                  <td><RunStatusBadge status={run.status} /></td>
                  <td>{formatDate(run.createdAt, locale)}</td>
                  <td><Link className="ops-link" to={`/projects/${encodeURIComponent(projectUuid)}/runs/${run.runUuid}`}>{t('viewDetails')}</Link></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        ) : null}
      </Panel>

      {dialogOpen ? (
        <Dialog title={t('startRun')} onClose={() => !submitting && setDialogOpen(false)}>
          <form className="ops-form" onSubmit={(event) => void submit(event)}>
            {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
            <Field label={t('publication')} hint={t('choosePublication')}>
              <select value={publicationUuid} onChange={(event) => setPublicationUuid(event.target.value)} required>
                {activePublications.map((publication) => <option key={publication.uuid} value={publication.uuid}>{publicationLabel(publication)}</option>)}
              </select>
            </Field>
            <div className="execution-idempotency-note">{t('idempotencyPrepared')}</div>
            <div className="ops-form-actions">
              <button className="ops-button ops-button-secondary" type="button" onClick={() => setDialogOpen(false)} disabled={submitting}>{t('close')}</button>
              <button className="ops-button" type="submit" disabled={submitting || !publicationUuid}>{submitting ? t('starting') : t('startRun')}</button>
            </div>
          </form>
        </Dialog>
      ) : null}
    </main>
  )
}
