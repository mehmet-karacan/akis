import { useMemo, useState } from 'react'
import { CheckCircle2, ChevronLeft, ChevronRight, CircleAlert, Database, LoaderCircle, ServerCog, TestTube2 } from 'lucide-react'
import { topologyApi, type ConnectionTestResult, type ConnectionVersion, type SecretReference } from './api'
import { initialConnectionVersionDraft, toCreateConnectionVersionRequest, validateConnectionVersionDraft, type ConnectionVersionDraft } from './connectionVersionModel'
import type { getTopologyCopy } from './copy'

interface Props {
  projectUuid: string
  connectionUuid: string
  secrets: SecretReference[]
  copy: ReturnType<typeof getTopologyCopy>
  onCreated(version: ConnectionVersion): Promise<void> | void
  onClose(): void
}

export function OracleConnectionVersionForm({ projectUuid, connectionUuid, secrets, copy: c, onCreated, onClose }: Props) {
  const [draft, setDraft] = useState<ConnectionVersionDraft>(initialConnectionVersionDraft)
  const [step, setStep] = useState(0)
  const [busy, setBusy] = useState<'create' | 'test' | ''>('')
  const [error, setError] = useState('')
  const [created, setCreated] = useState<ConnectionVersion | null>(null)
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null)
  const eligibleSecrets = useMemo(
    () => secrets.filter((item) => item.provider === 'ENV' && item.status === 'AKTIF'),
    [secrets],
  )
  const credential = useMemo(() => eligibleSecrets.find((item) => item.uuid === draft.credentialSecretReferenceUuid), [draft.credentialSecretReferenceUuid, eligibleSecrets])
  const steps = [c.endpointStep, c.credentialsStep, c.reviewStep]

  const update = <K extends keyof ConnectionVersionDraft>(key: K, value: ConnectionVersionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value }))
    setError('')
  }

  const next = () => {
    const invalid = validateConnectionVersionDraft(draft)
    const relevant = step === 0
      ? invalid.filter((item) => item !== 'credential')
      : invalid
    if (relevant.length) {
      setError(c.invalidConnectionFields)
      return
    }
    setStep((current) => Math.min(2, current + 1))
  }

  const create = async () => {
    if (validateConnectionVersionDraft(draft).length) {
      setError(c.invalidConnectionFields)
      return
    }
    setBusy('create')
    setError('')
    try {
      const version = await topologyApi.createVersion(projectUuid, connectionUuid, toCreateConnectionVersionRequest(draft))
      setCreated(version)
      await onCreated(version)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : c.saveFailed)
    } finally {
      setBusy('')
    }
  }

  const test = async () => {
    if (!created) return
    setBusy('test')
    setError('')
    setTestResult(null)
    try {
      setTestResult(await topologyApi.testOracle(projectUuid, connectionUuid, created.uuid))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : c.loadFailed)
    } finally {
      setBusy('')
    }
  }

  if (created) {
    return <div className="topology-version-success">
      <CheckCircle2 />
      <div><h3>{c.versionCreated}</h3><p>v{created.versionNumber} · {created.mode}</p></div>
      {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
      {testResult && <div className="topology-result topology-result--success"><CheckCircle2 /><div><strong>{c.testSuccess}</strong><span>{testResult.databaseProduct} {testResult.databaseVersion}</span><small>{testResult.driverName} {testResult.driverVersion}</small></div></div>}
      <div className="topology-form-actions">
        <button className="topology-button topology-button--quiet" type="button" onClick={onClose}>{c.done}</button>
        <button className="topology-button" type="button" onClick={() => void test()} disabled={busy === 'test'}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <TestTube2 />}{busy === 'test' ? c.testing : c.test}</button>
      </div>
      {created.mode === 'JNDI' && <p className="topology-security-note"><CircleAlert />{c.jndiRuntimeHint}</p>}
    </div>
  }

  return <form className="topology-version-wizard" onSubmit={(event) => { event.preventDefault(); if (step < 2) next(); else void create() }}>
    <ol className="topology-wizard-steps">{steps.map((label, index) => <li key={label} aria-current={index === step ? 'step' : undefined} className={index < step ? 'is-complete' : ''}><span>{index + 1}</span>{label}</li>)}</ol>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}

    {step === 0 && <div className="topology-form">
      <fieldset className="topology-choice"><legend>{c.connectionMode}</legend>
        <button type="button" aria-pressed={draft.mode === 'JDBC'} onClick={() => update('mode', 'JDBC')}><Database /><strong>JDBC</strong><small>{c.jdbcModeHint}</small></button>
        <button type="button" aria-pressed={draft.mode === 'JNDI'} onClick={() => update('mode', 'JNDI')}><ServerCog /><strong>JNDI</strong><small>{c.jndiModeHint}</small></button>
      </fieldset>
      {draft.mode === 'JDBC' ? <>
        <label className="topology-field"><span>{c.host} *</span><input required value={draft.host} onChange={(event) => update('host', event.target.value)} autoComplete="off" /></label>
        <div className="topology-form-row"><label className="topology-field"><span>{c.port} *</span><input required type="number" min="1" max="65535" value={draft.port} onChange={(event) => update('port', event.target.value)} /></label><label className="topology-field"><span>{c.transport} *</span><select required value={draft.transport} onChange={(event) => update('transport', event.target.value as ConnectionVersionDraft['transport'])}><option value="TCP">TCP</option><option value="TCPS" disabled>{c.tcpsPlanned}</option></select></label></div>
        <fieldset className="topology-segment"><legend>{c.connectIdentifier}</legend><button type="button" aria-pressed={draft.identifierType === 'SERVICE_NAME'} onClick={() => update('identifierType', 'SERVICE_NAME')}>{c.serviceName}</button><button type="button" aria-pressed={draft.identifierType === 'SID'} onClick={() => update('identifierType', 'SID')}>{c.sid}</button></fieldset>
        <label className="topology-field"><span>{draft.identifierType === 'SERVICE_NAME' ? c.serviceName : c.sid} *</span><input required value={draft.identifier} onChange={(event) => update('identifier', event.target.value)} autoComplete="off" /></label>
        <p className="topology-security-note">{c.driverManaged}</p>
      </> : <>
        <label className="topology-field"><span>{c.jndiName} *</span><input required value={draft.jndiName} onChange={(event) => update('jndiName', event.target.value)} placeholder="java:comp/env/jdbc/OracleMain" autoComplete="off" /></label>
        <p className="topology-security-note"><CircleAlert />{c.localJndiOnly} {c.jndiExecutionBlocked}</p>
      </>}
    </div>}

    {step === 1 && <div className="topology-form">
      {draft.mode === 'JDBC' ? <>
        <label className="topology-field"><span>{c.credentialSecret} *</span><select required value={draft.credentialSecretReferenceUuid} onChange={(event) => update('credentialSecretReferenceUuid', event.target.value)}><option value="">—</option>{eligibleSecrets.map((item) => <option key={item.uuid} value={item.uuid}>{item.name} · {item.code}</option>)}</select></label>
        <p className="topology-security-note">{c.noSecretValues}</p>
        {!eligibleSecrets.length && <div className="topology-inline-error"><CircleAlert />{c.secretRequiredHint}</div>}
      </> : <div className="topology-managed-note"><ServerCog /><div><strong>{c.containerManaged}</strong><p>{c.containerManagedHint}</p></div></div>}
    </div>}

    {step === 2 && <dl className="topology-review">
      <div><dt>{c.connectionMode}</dt><dd>{draft.mode}</dd></div>
      {draft.mode === 'JDBC' ? <><div><dt>{c.endpoint}</dt><dd>{draft.host}:{draft.port}</dd></div><div><dt>{c.connectIdentifier}</dt><dd>{draft.identifierType} · {draft.identifier}</dd></div><div><dt>{c.transport}</dt><dd>{draft.transport}</dd></div><div><dt>{c.credentialSecret}</dt><dd>{credential?.name ?? '—'}</dd></div><div><dt>{c.driver}</dt><dd>oracle.jdbc.OracleDriver · {c.systemManaged}</dd></div></> : <div><dt>{c.jndiName}</dt><dd>{draft.jndiName}</dd></div>}
    </dl>}

    <div className="topology-form-actions">
      <button className="topology-button topology-button--quiet" type="button" onClick={() => step === 0 ? onClose() : setStep((current) => current - 1)}><ChevronLeft />{step === 0 ? c.close : c.back}</button>
      {step < 2 ? <button className="topology-button" type="submit" disabled={step === 1 && draft.mode === 'JDBC' && !eligibleSecrets.length}>{c.continue}<ChevronRight /></button> : <button className="topology-button" type="submit" disabled={busy === 'create'}>{busy === 'create' ? <LoaderCircle className="is-spinning" /> : <CheckCircle2 />}{busy === 'create' ? c.creating : c.createVersion}</button>}
    </div>
  </form>
}
