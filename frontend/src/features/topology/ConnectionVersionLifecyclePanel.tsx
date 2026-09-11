import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Check,
  CheckCircle2,
  CircleAlert,
  Clipboard,
  Eye,
  EyeOff,
  LoaderCircle,
  ShieldCheck,
  TestTube2,
} from 'lucide-react'
import { ApiProblem } from '../../core/api/client'
import {
  topologyApi,
  type ConnectionTestAttempt,
  type ConnectionVersion,
} from './api'
import type { getTopologyCopy } from './copy'

interface Props {
  projectUuid: string
  connectionUuid: string
  version: ConnectionVersion
  copy: ReturnType<typeof getTopologyCopy>
  locale: string
  onVersionChanged(): Promise<void> | void
}

function template(value: string, replacements: Record<string, string | number>) {
  return Object.entries(replacements).reduce(
    (result, [key, replacement]) => result.replaceAll(`{{${key}}}`, String(replacement)),
    value,
  )
}

export function lifecycleLabel(
  status: ConnectionVersion['lifecycleStatus'],
  copy: ReturnType<typeof getTopologyCopy>,
) {
  if (status === 'ACTIVE') return copy.lifecycleActive
  if (status === 'TESTED') return copy.lifecycleTested
  return copy.lifecycleDraft
}

export function abbreviateFingerprint(value: string) {
  return value.length > 24 ? `${value.slice(0, 12)}…${value.slice(-8)}` : value
}

function attemptLabel(attempt: ConnectionTestAttempt, copy: ReturnType<typeof getTopologyCopy>) {
  if (attempt.outcome === 'PASSED') return copy.testPassed
  if (attempt.outcome === 'TARGET_MISMATCH') return copy.targetMismatch
  return copy.testFailed
}

function localizedError(error: unknown, copy: ReturnType<typeof getTopologyCopy>, fallback: string) {
  if (error instanceof ApiProblem) {
    if (error.code === 'CONNECTION_VERSION_STATE_CONFLICT') return copy.activationConflict
    if (error.code === 'ORACLE_TARGET_MISMATCH') return copy.targetMismatch
  }
  return error instanceof Error && error.message ? error.message : fallback
}

function Fingerprint({ value, copy }: { value: string; copy: ReturnType<typeof getTopologyCopy> }) {
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState(false)

  const copyValue = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return <div className="topology-fingerprint">
    <span>{copy.targetFingerprint}</span>
    <code>{revealed ? value : abbreviateFingerprint(value)}</code>
    <div>
      <button className="topology-icon-button" type="button" aria-label={revealed ? copy.hideFingerprint : copy.revealFingerprint} onClick={() => setRevealed((current) => !current)}>
        {revealed ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
      </button>
      <button className="topology-icon-button" type="button" aria-label={copied ? copy.fingerprintCopied : copy.copyFingerprint} onClick={() => void copyValue()}>
        {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
      </button>
      <span className="sr-only" aria-live="polite">{copied ? copy.fingerprintCopied : ''}</span>
    </div>
  </div>
}

export function ConnectionVersionLifecyclePanel({ projectUuid, connectionUuid, version, copy: c, locale, onVersionChanged }: Props) {
  const [attempts, setAttempts] = useState<ConnectionTestAttempt[]>([])
  const [loadingEvidence, setLoadingEvidence] = useState(true)
  const [busy, setBusy] = useState<'test' | 'activate' | ''>('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmingActivation, setConfirmingActivation] = useState(false)
  const formatter = useMemo(
    () => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }),
    [locale],
  )

  const loadEvidence = useCallback(async () => {
    setLoadingEvidence(true)
    try {
      setAttempts(await topologyApi.listConnectionVersionTests(projectUuid, connectionUuid, version.uuid, 20))
    } catch {
      setError(c.evidenceLoadFailed)
    } finally {
      setLoadingEvidence(false)
    }
  }, [c.evidenceLoadFailed, connectionUuid, projectUuid, version.uuid])

  useEffect(() => {
    let active = true
    setLoadingEvidence(true)
    setAttempts([])
    setError('')
    topologyApi.listConnectionVersionTests(projectUuid, connectionUuid, version.uuid, 20)
      .then((items) => { if (active) setAttempts(items) })
      .catch(() => { if (active) setError(c.evidenceLoadFailed) })
      .finally(() => { if (active) setLoadingEvidence(false) })
    return () => { active = false }
  }, [c.evidenceLoadFailed, connectionUuid, projectUuid, version.uuid])

  const refreshVersion = async () => {
    try {
      await onVersionChanged()
    } catch {
      setError((current) => current || c.loadFailed)
    }
  }

  const runTest = async () => {
    setBusy('test')
    setError('')
    setNotice('')
    try {
      const attempt = await topologyApi.testConnectionVersion(projectUuid, connectionUuid, version.uuid)
      setAttempts((current) => [attempt, ...current.filter((item) => item.uuid !== attempt.uuid)])
    } catch (reason) {
      setError(localizedError(reason, c, c.connectionTestFailed))
      await loadEvidence()
    } finally {
      await refreshVersion()
      setBusy('')
    }
  }

  const activate = async () => {
    if (!version.latestSuccessfulTestUuid) return
    setBusy('activate')
    setError('')
    setNotice('')
    try {
      await topologyApi.activateConnectionVersion(projectUuid, connectionUuid, version.uuid, {
        testUuid: version.latestSuccessfulTestUuid,
        expectedStateVersion: version.lifecycleVersion,
      })
      setNotice(c.versionActivated)
      setConfirmingActivation(false)
      await refreshVersion()
    } catch (reason) {
      setError(localizedError(reason, c, c.activationFailed))
    } finally {
      setBusy('')
    }
  }

  const latestAttempt = attempts[0]
  const fingerprint = latestAttempt?.targetFingerprint ?? version.targetFingerprint
  const canActivate = version.lifecycleStatus === 'TESTED'
    && version.runtimeCapability === 'EXECUTABLE'
    && Boolean(version.latestSuccessfulTestUuid)

  return <section className="topology-lifecycle" aria-label={`${c.version} ${version.versionNumber}`}>
    <header className="topology-lifecycle-heading">
      <div>
        <span className={`topology-lifecycle-badge topology-lifecycle-badge--${version.lifecycleStatus.toLowerCase()}`}>
          {lifecycleLabel(version.lifecycleStatus, c)}
        </span>
        {version.runtimeCapability === 'TEST_DISCOVERY_ONLY' && <span className="topology-runtime-badge">{c.runtimeLimited}</span>}
      </div>
      <div className="topology-lifecycle-times">
        <span>{version.testedAt ? `${c.testedAt}: ${formatter.format(new Date(version.testedAt))}` : c.notTested}</span>
        {version.activatedAt && <span>{c.activatedAt}: {formatter.format(new Date(version.activatedAt))}</span>}
      </div>
    </header>

    {notice && <div className="topology-notice" role="status"><CheckCircle2 aria-hidden="true" />{notice}</div>}
    {error && <div className="topology-inline-error" role="alert"><CircleAlert aria-hidden="true" />{error}</div>}

    <div className="topology-evidence">
      <div className="topology-evidence-heading"><strong>{c.latestTestEvidence}</strong></div>
      {loadingEvidence ? <div className="topology-loading topology-loading--compact" role="status"><LoaderCircle className="is-spinning" aria-hidden="true" />{c.loading}</div> : !latestAttempt ? <p className="topology-muted">{c.noTestEvidence}</p> : <article className={`topology-test-attempt topology-test-attempt--${latestAttempt.outcome.toLowerCase()}`}>
        <div className="topology-test-attempt-heading">
          <span className="topology-test-outcome">{attemptLabel(latestAttempt, c)}</span>
          <span>{template(c.attempt, { number: latestAttempt.attemptNumber })} · {template(c.duration, { duration: latestAttempt.durationMs })}</span>
          <time dateTime={latestAttempt.completedAt}>{formatter.format(new Date(latestAttempt.completedAt))}</time>
        </div>
        {latestAttempt.probe && <p>{latestAttempt.probe.databaseProduct} {latestAttempt.probe.databaseVersion} · {latestAttempt.probe.driverName} {latestAttempt.probe.driverVersion}</p>}
        {latestAttempt.errorCode && <code>{latestAttempt.errorCode}</code>}
        {latestAttempt.targetIdentityVersion != null && <small>{template(c.targetIdentity, { version: latestAttempt.targetIdentityVersion })}</small>}
      </article>}
      {fingerprint && <Fingerprint value={fingerprint} copy={c} />}
    </div>

    {version.runtimeCapability === 'TEST_DISCOVERY_ONLY' && <p className="topology-security-note"><CircleAlert aria-hidden="true" />{c.jndiExecutionBlocked}</p>}

    {confirmingActivation && <div className="topology-activation-confirm" role="group" aria-label={c.confirmActivation}>
      <ShieldCheck aria-hidden="true" />
      <div><strong>{c.activationPrompt}</strong><p>{c.activationEvidence}</p></div>
      <button className="topology-button topology-button--quiet" type="button" onClick={() => setConfirmingActivation(false)} disabled={busy === 'activate'}>{c.cancel}</button>
      <button className="topology-button" type="button" onClick={() => void activate()} disabled={busy === 'activate'}>{busy === 'activate' ? <LoaderCircle className="is-spinning" aria-hidden="true" /> : <ShieldCheck aria-hidden="true" />}{busy === 'activate' ? c.activating : c.confirmActivation}</button>
    </div>}

    <div className="topology-lifecycle-actions">
      <button className={version.lifecycleStatus === 'DRAFT' ? 'topology-button' : 'topology-button topology-button--quiet'} type="button" onClick={() => void runTest()} disabled={busy !== ''}>
        {busy === 'test' ? <LoaderCircle className="is-spinning" aria-hidden="true" /> : <TestTube2 aria-hidden="true" />}
        {busy === 'test' ? c.testing : version.lifecycleStatus === 'DRAFT' ? c.testVersion : c.testAgain}
      </button>
      {canActivate && !confirmingActivation && <button className="topology-button" type="button" onClick={() => setConfirmingActivation(true)} disabled={busy !== ''}><ShieldCheck aria-hidden="true" />{c.activateVersion}</button>}
      {version.lifecycleStatus === 'TESTED' && version.runtimeCapability === 'EXECUTABLE' && !version.latestSuccessfulTestUuid && <span className="topology-muted">{c.activationRequiresTest}</span>}
    </div>
  </section>
}
