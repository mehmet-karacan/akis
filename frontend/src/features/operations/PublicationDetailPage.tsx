import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput, Radio as AntRadio, Tabs, Tag } from 'antd'
import { ArrowLeft, CalendarClock, CheckCircle2, CircleAlert, CircleX, Database, FileJson2, Hourglass, Play, Rocket, Scale, ShieldAlert, ShieldCheck, Workflow } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { operationsApi } from './api'
import { CopyValue, Field, StatusBadge } from './OperationsUi'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { connectionStatusTagStyles } from '../connections/presentation'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import { useOperationsI18n } from './i18n'
import type { ApprovalDecision } from './types'
import type { ProcedurePilotVerification, ProcedureSourcePreflight, ProcedureTargetPreflight } from './types'
import { apiErrorMessage, formatDate, redactSensitiveValues } from './utils'
import { useRemoteData } from './useRemoteData'
import { executionCodeLabel } from '../execution/i18n'
import { executionApi } from '../execution/api'

export function PublicationDetailPage() {
  const { publicationUuid = '' } = useParams(); const projectUuid = useCurrentProjectUuid()
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
  const navigate = useNavigate()
  const [starting, setStarting] = useState(false)
  const [batchRows, setBatchRows] = useState('')
  const [startError, setStartError] = useState('')
  // Only an active publication can run; the backend enforces the same gate, the idempotency key keeps double clicks from starting twice.
  const canRun = publication?.status === 'AKTIF'
  const startRun = async () => {
    if (!publication || !projectUuid) return
    setStarting(true); setStartError('')
    try {
      const batch = Number.parseInt(batchRows, 10)
      const run = await executionApi.startRun(projectUuid, publication.uuid, crypto.randomUUID(), Number.isFinite(batch) && batch > 0 ? { batchRows: Math.min(batch, 5000) } : undefined)
      navigate(`/project/operations/runs/${encodeURIComponent(run.runUuid)}`)
    } catch (error) { setStartError(apiErrorMessage(error, t('requestFailed'))) }
    finally { setStarting(false) }
  }
  // The backend decides when approval is required (production risk or a step flagged for approval); the UI follows the status.
  const canApprove = publication?.status === 'ONAY_BEKLIYOR'
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

  const tone = publication ? (['AKTIF', 'ETKIN'].includes(publication.status) ? 'success' : publication.status === 'ONAY_BEKLIYOR' ? 'warning' : publication.status === 'IPTAL' ? 'danger' : 'neutral') : 'neutral'
  const statusIcon = tone === 'success' ? <CheckCircle2 size={12} /> : tone === 'warning' ? <Hourglass size={12} /> : tone === 'danger' ? <CircleX size={12} /> : <CircleAlert size={12} />
  const statusText = publication ? (['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(publication.status) ? t(`status_${publication.status}` as Parameters<typeof t>[0]) : publication.status) : ''
  const backLink = <div className="ops-header-actions">
    <Link className="ops-button ops-button-secondary" to="/project/publications"><ArrowLeft aria-hidden="true" /> {t('backToPublications')}</Link>
    {canRun && <label className="ops-batch-field" title={t('batchRowsHint')}><span>{t('batchRows')}</span><input type="number" min={1} max={5000} inputMode="numeric" value={batchRows} placeholder="—" onChange={(event) => setBatchRows(event.target.value)} /></label>}
    {canRun && <AntActionButton tone="primary" type="button" icon={<Play size={16} />} busy={starting} onClick={() => void startRun()}>{starting ? t('starting') : t('run')}</AntActionButton>}
  </div>

  const approvalTab = publication ? <section className="connection-detail-section">
    {successActor ? <div className="ops-alert ops-alert-success" role="status"><CheckCircle2 aria-hidden="true" /> {t('decisionRecorded', { actor: successActor })}</div> : null}
    {canApprove || canWithdraw ? (
      <form className="ops-form ops-approval-form" onSubmit={(event) => void submit(event)}>
        {submitError ? <div className="ops-alert ops-alert-error" role="alert">{submitError}</div> : null}
        <fieldset className="ops-decision-fieldset">
          <legend>{t('approval')}</legend>
          <div className="ops-decision-options">
            {canApprove ? <>
              <label><AntRadio name="decision" value="ONAY" checked={decision === 'ONAY'} onChange={() => setDecision('ONAY')} /> {t('approve')}</label>
              <label><AntRadio name="decision" value="RED" checked={decision === 'RED'} onChange={() => setDecision('RED')} /> {t('reject')}</label>
            </> : null}
            {canWithdraw ? <label><AntRadio name="decision" value="GERI_CEK" checked /> {t('withdraw')}</label> : null}
          </div>
        </fieldset>
        <Field label={t('reason')} error={reasonRequired && !reason.trim() ? t('reasonRequired') : undefined}>
          <AntInput.TextArea rows={4} value={reason} onChange={(event) => setReason(event.target.value)} aria-required={reasonRequired} />
        </Field>
        <AntActionButton tone="primary" className={`ops-button ${activeDecision === 'RED' ? 'ops-button-danger' : activeDecision === 'GERI_CEK' ? 'ops-button-warning' : ''}`} type="submit" disabled={submitting || (reasonRequired && !reason.trim())}>
          {submitting ? t('submitting') : t('submitDecision')}
        </AntActionButton>
      </form>
    ) : <p className="ops-muted">{t('noApprovalNeeded')}</p>}
    <h3>{t('technicalDetails')}</h3>
    <dl className="ops-kv">
      <dt>{t('releaseHash')}</dt><dd><CopyValue value={publication.releaseHash} /></dd>
      <dt>{t('scenarioUuid')}</dt><dd><CopyValue value={publication.scenarioUuid} /></dd>
      <dt>{t('definitionUuid')}</dt><dd><CopyValue value={publication.definitionUuid} /></dd>
      <dt>{t('definitionVersionUuid')}</dt><dd><CopyValue value={publication.definitionVersionUuid} /></dd>
      <dt>{t('environmentUuid')}</dt><dd><CopyValue value={publication.environmentUuid} /></dd>
    </dl>
  </section> : null

  const readinessTab = publication ? <section className="connection-detail-section">
    <p className="ops-muted ops-preflight-help">{t('procedureReadinessHelp')}</p>
    {preflightError ? <div className="ops-alert ops-alert-error" role="alert">{preflightError}</div> : null}
    <div className="ops-preflight-grid">
      <section className="ops-preflight-card">
        <header><Database aria-hidden="true" /><strong>{t('verifySource')}</strong><StatusBadge value={sourcePreflight ? t('verified') : t('notVerified')} /></header>
        {sourcePreflight ? <dl className="ops-kv">
          <dt>{t('readOnlyProof')}</dt><dd>{sourcePreflight.sourceReadOnly && !sourcePreflight.targetSessionOpened ? t('passed') : t('failed')}</dd>
          <dt>{t('rowsObserved')}</dt><dd>{sourcePreflight.observedRowCount}</dd>
          <dt>{t('rowLimit')}</dt><dd>{sourcePreflight.maximumRows}</dd>
          <dt>{t('duration')}</dt><dd>{sourcePreflight.durationMs} ms</dd>
        </dl> : <p className="ops-muted">{t('notVerified')}</p>}
        <AntActionButton tone="ghost" className="ops-button ops-button-secondary" type="button" disabled={preflightBusy !== null} onClick={() => void preflight('source')}><ShieldCheck aria-hidden="true" /> {preflightBusy === 'source' ? t('verifying') : t('verifySource')}</AntActionButton>
      </section>
      <section className="ops-preflight-card">
        <header><Database aria-hidden="true" /><strong>{t('verifyTarget')}</strong><StatusBadge value={targetPreflight ? t('verified') : t('notVerified')} /></header>
        {targetPreflight ? <dl className="ops-kv">
          <dt>{t('oracleIdentity')}</dt><dd>{targetPreflight.databaseUniqueName} / {targetPreflight.containerName}</dd>
          <dt>{t('targetUser')}</dt><dd>{targetPreflight.currentUser}</dd>
          <dt>{t('targetPrivileges')}</dt><dd>{targetPreflight.ownsTarget && targetPreflight.canTruncate && targetPreflight.canInsert && targetPreflight.canExecuteDbmsStats ? t('passed') : t('failed')}</dd>
          <dt>{t('readOnlyProof')}</dt><dd>{targetPreflight.targetReadOnly && !targetPreflight.sourceSessionOpened ? t('passed') : t('failed')}</dd>
        </dl> : <p className="ops-muted">{t('notVerified')}</p>}
        <AntActionButton tone="ghost" className="ops-button ops-button-secondary" type="button" disabled={preflightBusy !== null} onClick={() => void preflight('target')}><ShieldCheck aria-hidden="true" /> {preflightBusy === 'target' ? t('verifying') : t('verifyTarget')}</AntActionButton>
      </section>
      {publication.status === 'AKTIF' ? <section className="ops-preflight-card ops-preflight-acceptance">
        <header><Scale aria-hidden="true" /><strong>{t('acceptanceEvidence')}</strong><StatusBadge value={verification?.matches ? t('verified') : t('notVerified')} /></header>
        {verification ? <dl className="ops-kv">
          <dt>{t('sourceRows')}</dt><dd>{verification.sourceRowCount}</dd>
          <dt>{t('targetRows')}</dt><dd>{verification.targetRowCount}</dd>
          <dt>{t('payloadMatch')}</dt><dd>{verification.matches ? t('passed') : t('failed')}</dd>
          <dt>{t('duration')}</dt><dd>{verification.durationMs} ms</dd>
        </dl> : <p className="ops-muted">{t('notVerified')}</p>}
        <AntActionButton tone="ghost" className="ops-button" type="button" disabled={preflightBusy !== null} onClick={() => void verifyPilot()}><Scale aria-hidden="true" /> {preflightBusy === 'verification' ? t('verifying') : t('compareSourceTarget')}</AntActionButton>
      </section> : null}
    </div>
    {publication.status === 'AKTIF' ? <Link className="ops-button ops-runs-link" to="/project/operations">{t('openRuns')}</Link> : null}
  </section> : null

  const manifestTab = publication ? <section className="connection-detail-section">
    <h3>{t('dependencySummary')}</h3>
    <code className="ops-summary-code">{publication.dependencySummary}</code>
    <h3>{t('manifest')}</h3>
    <pre className="ops-code-block" tabIndex={0}>{JSON.stringify(redactSensitiveValues(publication.physicalManifest), null, 2)}</pre>
  </section> : null

  return (
    <section className="page-stack connections-page publication-detail-page">
      <section className="connection-management-panel"><PageHeader icon={<Rocket />} eyebrow={publication ? `#${publication.publicationNumber} · ${publication.environmentCode}` : t('publicationDetail')} title={publication ? `${t('publicationDetail')} #${publication.publicationNumber}` : t('publicationDetail')} description={publication ? `${publication.environmentCode} · ${executionCodeLabel(publication.environmentRisk, locale)}` : ''} actions={backLink} /></section>
      {remote.loading ? <AsyncState state="loading" title={t('loading')} /> : null}
      {!remote.loading && remote.error ? <AsyncState state="error" title={apiErrorMessage(remote.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void remote.reload()} /> : null}
      {startError ? <div className="ops-alert ops-alert-error" role="alert">{startError}</div> : null}
      {publication ? <>
        <SummaryStrip ariaLabel={t('publicationContext')} items={[
          { label: t('status'), value: <Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={statusIcon}><span className="connection-status-tag-label">{statusText}</span></Tag>, icon: <Rocket />, tone },
          { label: t('environment'), value: publication.environmentCode, icon: <Workflow />, tone: 'neutral' },
          { label: t('risk'), value: executionCodeLabel(publication.environmentRisk, locale), icon: <ShieldAlert />, tone: publication.environmentRisk === 'URETIM' ? 'danger' : 'info' },
          { label: t('createdAt'), value: formatDate(publication.createdAt, locale), icon: <CalendarClock />, tone: 'neutral' },
          { label: t('publishedAt'), value: formatDate(publication.publishedAt, locale), icon: <CheckCircle2 />, tone: publication.publishedAt ? 'success' : 'neutral' },
        ]} />
        <Tabs items={[
          { key: 'approval', label: <span className="connection-tab-label connection-tab-definition"><ShieldCheck size={16} />{t('approval')}</span>, children: approvalTab },
          { key: 'readiness', label: <span className="connection-tab-label connection-tab-physical"><Scale size={16} />{t('procedureReadiness')}</span>, children: readinessTab },
          { key: 'manifest', label: <span className="connection-tab-label"><FileJson2 size={16} />{t('manifest')}</span>, children: manifestTab },
        ]} />
      </> : null}
    </section>
  )
}
