import { ArrowLeft, CheckCircle2, Database, Scale, ShieldCheck } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { operationsApi } from './api'
import { CopyValue, ErrorState, Field, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import type { ApprovalDecision } from './types'
import type { ProcedurePilotVerification, ProcedureSourcePreflight, ProcedureTargetPreflight } from './types'
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
  const [sourcePreflight, setSourcePreflight] = useState<ProcedureSourcePreflight | null>(null)
  const [targetPreflight, setTargetPreflight] = useState<ProcedureTargetPreflight | null>(null)
  const [verification, setVerification] = useState<ProcedurePilotVerification | null>(null)
  const [preflightBusy, setPreflightBusy] = useState<'source' | 'target' | 'verification' | null>(null)
  const [preflightError, setPreflightError] = useState('')
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

  const verifyPilot = async () => {
    setPreflightBusy('verification')
    setPreflightError('')
    try {
      setVerification(await operationsApi.verifyProcedurePilot(projectUuid, publicationUuid))
    } catch (error) {
      setPreflightError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setPreflightBusy(null)
    }
  }

  const preflight = async (side: 'source' | 'target') => {
    setPreflightBusy(side)
    setPreflightError('')
    try {
      if (side === 'source') {
        setSourcePreflight(await operationsApi.preflightProcedureSource(projectUuid, publicationUuid))
      } else {
        setTargetPreflight(await operationsApi.preflightProcedureTarget(projectUuid, publicationUuid))
      }
    } catch (error) {
      setPreflightError(apiErrorMessage(error, t('requestFailed')))
    } finally {
      setPreflightBusy(null)
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

          <Panel title={t('procedureReadiness')}>
            <p className="ops-muted ops-preflight-help">{t('procedureReadinessHelp')}</p>
            {preflightError ? <div className="ops-alert ops-alert-error" role="alert">{preflightError}</div> : null}
            <div className="ops-preflight-grid">
              <section className="ops-preflight-card">
                <header><Database aria-hidden="true" /><strong>SKY</strong><StatusBadge value={sourcePreflight ? t('verified') : t('notVerified')} /></header>
                {sourcePreflight ? (
                  <dl className="ops-kv">
                    <dt>{t('readOnlyProof')}</dt><dd>{sourcePreflight.sourceReadOnly && !sourcePreflight.targetSessionOpened ? t('passed') : t('failed')}</dd>
                    <dt>{t('rowsObserved')}</dt><dd>{sourcePreflight.observedRowCount}</dd>
                    <dt>{t('rowLimit')}</dt><dd>{sourcePreflight.maximumRows}</dd>
                    <dt>{t('duration')}</dt><dd>{sourcePreflight.durationMs} ms</dd>
                  </dl>
                ) : <p className="ops-muted">{t('notVerified')}</p>}
                <button className="ops-button ops-button-secondary" type="button" disabled={preflightBusy !== null} onClick={() => void preflight('source')}>
                  <ShieldCheck aria-hidden="true" /> {preflightBusy === 'source' ? t('verifying') : t('verifySource')}
                </button>
              </section>
              <section className="ops-preflight-card">
                <header><Database aria-hidden="true" /><strong>GPU</strong><StatusBadge value={targetPreflight ? t('verified') : t('notVerified')} /></header>
                {targetPreflight ? (
                  <dl className="ops-kv">
                    <dt>{t('oracleIdentity')}</dt><dd>{targetPreflight.databaseUniqueName} / {targetPreflight.containerName}</dd>
                    <dt>{t('targetUser')}</dt><dd>{targetPreflight.currentUser}</dd>
                    <dt>{t('targetPrivileges')}</dt><dd>{targetPreflight.ownsTarget && targetPreflight.canTruncate && targetPreflight.canInsert && targetPreflight.canExecuteDbmsStats ? t('passed') : t('failed')}</dd>
                    <dt>{t('readOnlyProof')}</dt><dd>{targetPreflight.targetReadOnly && !targetPreflight.sourceSessionOpened ? t('passed') : t('failed')}</dd>
                  </dl>
                ) : <p className="ops-muted">{t('notVerified')}</p>}
                <button className="ops-button ops-button-secondary" type="button" disabled={preflightBusy !== null} onClick={() => void preflight('target')}>
                  <ShieldCheck aria-hidden="true" /> {preflightBusy === 'target' ? t('verifying') : t('verifyTarget')}
                </button>
              </section>
            </div>
            {publication.status === 'AKTIF' ? (
              <section className="ops-preflight-card ops-preflight-acceptance">
                <header><Scale aria-hidden="true" /><strong>{t('acceptanceEvidence')}</strong><StatusBadge value={verification?.matches ? t('verified') : t('notVerified')} /></header>
                {verification ? (
                  <dl className="ops-kv">
                    <dt>{t('sourceRows')}</dt><dd>{verification.sourceRowCount}</dd>
                    <dt>{t('targetRows')}</dt><dd>{verification.targetRowCount}</dd>
                    <dt>{t('payloadMatch')}</dt><dd>{verification.matches ? t('passed') : t('failed')}</dd>
                    <dt>{t('duration')}</dt><dd>{verification.durationMs} ms</dd>
                  </dl>
                ) : <p className="ops-muted">{t('notVerified')}</p>}
                <button className="ops-button" type="button" disabled={preflightBusy !== null} onClick={() => void verifyPilot()}>
                  <Scale aria-hidden="true" /> {preflightBusy === 'verification' ? t('verifying') : t('compareSourceTarget')}
                </button>
              </section>
            ) : null}
            {publication.status === 'AKTIF' ? <Link className="ops-button ops-runs-link" to={`/projects/${encodeURIComponent(projectUuid)}/runs`}>{t('openRuns')}</Link> : null}
          </Panel>

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
