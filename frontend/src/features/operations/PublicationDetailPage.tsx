import { ArrowLeft, CheckCircle2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { operationsApi } from './api'
import { CopyValue, ErrorState, Field, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import type { ApprovalDecision } from './types'
import { apiErrorMessage, formatDate, redactSensitiveValues } from './utils'
import { useRemoteData } from './useRemoteData'

export function PublicationDetailPage() {
  const { projectUuid = '', publicationUuid = '' } = useParams()
  const { t, locale } = useOperationsI18n()
  const remote = useRemoteData(
    () => operationsApi.getPublication(projectUuid, publicationUuid),
    [projectUuid, publicationUuid],
  )
  const [decision, setDecision] = useState<ApprovalDecision>('ONAY')
  const [reason, setReason] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [successActor, setSuccessActor] = useState('')
  const publication = remote.data
  const canApprove = publication?.environmentRisk === 'URETIM' && publication.status === 'ONAY_BEKLIYOR'
  const canWithdraw = publication?.environmentRisk === 'URETIM' && publication.status === 'AKTIF'
  const activeDecision: ApprovalDecision = canWithdraw ? 'GERI_CEK' : decision
  const reasonRequired = activeDecision === 'RED' || activeDecision === 'GERI_CEK'

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (reasonRequired && !reason.trim()) return
    setSubmitting(true)
    setSubmitError('')
    setSuccessActor('')
    try {
      const result = await operationsApi.decidePublication(
        projectUuid,
        publicationUuid,
        activeDecision,
        reason,
      )
      remote.setData(result.publication)
      setSuccessActor(result.approval.actorName)
      setReason('')
    } catch (error) {
      setSubmitError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="ops-page">
      <PageHeader
        title={t('publicationDetail')}
        description={publication ? `#${publication.publicationNumber} · ${publication.environmentCode}` : ''}
        actions={(
          <Link className="ops-button ops-button-secondary" to={`/projects/${encodeURIComponent(projectUuid)}/publications`}>
            <ArrowLeft aria-hidden="true" /> {t('backToPublications')}
          </Link>
        )}
      />

      {remote.loading ? <Panel><LoadingState /></Panel> : null}
      {!remote.loading && remote.error ? (
        <Panel><ErrorState message={apiErrorMessage(remote.error, t('requestFailed'))} onRetry={() => void remote.reload()} /></Panel>
      ) : null}

      {publication ? (
        <>
          <div className="ops-detail-grid">
            <Panel title={t('publicationContext')}>
              <dl className="ops-kv">
                <dt>{t('status')}</dt><dd><StatusBadge value={publication.status} /></dd>
                <dt>{t('releaseHash')}</dt><dd><CopyValue value={publication.releaseHash} /></dd>
                <dt>{t('scenarioUuid')}</dt><dd><CopyValue value={publication.scenarioUuid} /></dd>
                <dt>{t('definitionUuid')}</dt><dd><CopyValue value={publication.definitionUuid} /></dd>
                <dt>{t('definitionVersionUuid')}</dt><dd><CopyValue value={publication.definitionVersionUuid} /></dd>
                <dt>{t('environment')}</dt><dd>{publication.environmentCode} · {publication.environmentRisk}</dd>
                <dt>{t('environmentUuid')}</dt><dd><CopyValue value={publication.environmentUuid} /></dd>
                <dt>{t('createdAt')}</dt><dd>{formatDate(publication.createdAt, locale)}</dd>
                <dt>{t('publishedAt')}</dt><dd>{formatDate(publication.publishedAt, locale)}</dd>
              </dl>
            </Panel>

            <Panel title={t('approval')}>
              {successActor ? (
                <div className="ops-alert ops-alert-success" role="status">
                  <CheckCircle2 aria-hidden="true" /> {t('decisionRecorded', { actor: successActor })}
                </div>
              ) : null}
              {canApprove || canWithdraw ? (
                <form className="ops-form ops-approval-form" onSubmit={(event) => void submit(event)}>
                  {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
                  <fieldset className="ops-decision-fieldset">
                    <legend>{t('approval')}</legend>
                    <div className="ops-decision-options">
                      {canApprove ? (
                        <>
                          <label><input type="radio" name="decision" value="ONAY" checked={decision === 'ONAY'} onChange={() => setDecision('ONAY')} /> {t('approve')}</label>
                          <label><input type="radio" name="decision" value="RED" checked={decision === 'RED'} onChange={() => setDecision('RED')} /> {t('reject')}</label>
                        </>
                      ) : null}
                      {canWithdraw ? <label><input type="radio" name="decision" value="GERI_CEK" checked readOnly /> {t('withdraw')}</label> : null}
                    </div>
                  </fieldset>
                  <Field label={t('reason')} error={reasonRequired && !reason.trim() ? t('reasonRequired') : undefined}>
                    <textarea rows={4} value={reason} onChange={(event) => setReason(event.target.value)} aria-required={reasonRequired} />
                  </Field>
                  <button className={`ops-button ${activeDecision === 'RED' ? 'ops-button-danger' : activeDecision === 'GERI_CEK' ? 'ops-button-warning' : ''}`} type="submit" disabled={submitting || (reasonRequired && !reason.trim())}>
                    {submitting ? t('submitting') : t('submitDecision')}
                  </button>
                </form>
              ) : <p className="ops-muted">{t('noApprovalNeeded')}</p>}
            </Panel>
          </div>

          <Panel title={t('dependencySummary')}>
            <code className="ops-summary-code">{publication.dependencySummary}</code>
          </Panel>
          <Panel title={t('manifest')}>
            <pre className="ops-code-block" tabIndex={0}>{JSON.stringify(redactSensitiveValues(publication.physicalManifest), null, 2)}</pre>
          </Panel>
        </>
      ) : null}
    </main>
  )
}
