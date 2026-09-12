import { useMemo, useState, type FormEvent } from 'react'
import { CheckCircle2, CircleAlert, Database, LoaderCircle, Save, ServerCog, ShieldCheck } from 'lucide-react'
import { topologyApi, type DraftConnectionTestResult } from './api'
import { initialConnectionVersionDraft, toCreateConnectionVersionRequest, validateConnectionVersionDraft, type ConnectionVersionDraft } from './connectionVersionModel'
import type { getTopologyCopy } from './copy'
import { DatabaseProviderIcon } from './DatabaseProviderIcon'

interface Props {
  projectUuid: string
  copy: ReturnType<typeof getTopologyCopy>
  locale: string
  onConnectionCreated(connectionUuid: string): Promise<void>
  onClose(): void
}

const fingerprint = (name: string, code: string, description: string, draft: ConnectionVersionDraft) =>
  JSON.stringify({ name: name.trim(), code: code.trim(), description: description.trim(), ...draft })

export function OracleConnectionCreateForm({ projectUuid, copy: c, onConnectionCreated, onClose }: Props) {
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [draft, setDraft] = useState<ConnectionVersionDraft>(initialConnectionVersionDraft)
  const [busy, setBusy] = useState<'test' | 'save' | ''>('')
  const [error, setError] = useState('')
  const [testedFingerprint, setTestedFingerprint] = useState('')
  const [testResult, setTestResult] = useState<DraftConnectionTestResult | null>(null)
  const currentFingerprint = useMemo(() => fingerprint(name, code, description, draft), [name, code, description, draft])
  const tested = Boolean(testResult?.connected && testedFingerprint === currentFingerprint)

  const invalidate = () => { setTestResult(null); setTestedFingerprint(''); setError('') }
  const update = <K extends keyof ConnectionVersionDraft>(key: K, value: ConnectionVersionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value })); invalidate()
  }
  const valid = () => {
    if (!name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code) || validateConnectionVersionDraft(draft).length) {
      setError(c.invalidConnectionFields); return false
    }
    return true
  }
  const test = async () => {
    if (!valid()) return
    setBusy('test'); setError('')
    try {
      const result = await topologyApi.testOracleDraftConnection(projectUuid, toCreateConnectionVersionRequest(draft))
      setTestResult(result); setTestedFingerprint(currentFingerprint)
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.connectionTestFailed) }
    finally { setBusy('') }
  }
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!valid() || !tested) { setError(c.testBeforeSave); return }
    setBusy('save'); setError('')
    try {
      const result = await topologyApi.createOracleConnection(projectUuid, {
        name: name.trim(), code: code.trim(), description: description.trim() || undefined,
        initialVersion: toCreateConnectionVersionRequest(draft),
      })
      await onConnectionCreated(result.connection.uuid); onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.saveFailed) }
    finally { setBusy('') }
  }

  return <form className="topology-connection-form" onSubmit={(event) => void save(event)}>
    <section className="topology-form-section">
      <header><span>1</span><div><h3>{c.providerSelection}</h3><p>{c.providerHint}</p></div></header>
      <button className="topology-provider-choice" type="button" aria-pressed="true">
        <DatabaseProviderIcon databaseType="ORACLE" /><span><strong>Oracle</strong><small>Oracle Database 19c · JDBC / JNDI</small></span><CheckCircle2 />
      </button>
    </section>
    <section className="topology-form-section">
      <header><span>2</span><div><h3>{c.connectionDefinition}</h3></div></header>
      <div className="topology-form topology-form--grid">
        <label className="topology-field"><span>{c.name} *</span><input required value={name} onChange={(e) => { setName(e.target.value); invalidate() }} /></label>
        <label className="topology-field"><span>{c.code} *</span><input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={code} onChange={(e) => { setCode(e.target.value.toUpperCase()); invalidate() }} /></label>
        <label className="topology-field topology-field--wide"><span>{c.description} ({c.optional.toLowerCase()})</span><textarea rows={2} value={description} onChange={(e) => { setDescription(e.target.value); invalidate() }} /></label>
      </div>
    </section>
    <section className="topology-form-section">
      <header><span>3</span><div><h3>{c.oracleConnectionDetails}</h3></div></header>
      <fieldset className="topology-choice"><legend>{c.connectionMode}</legend>
        <button type="button" aria-pressed={draft.mode === 'JDBC'} onClick={() => update('mode', 'JDBC')}><Database /><strong>JDBC</strong><small>{c.jdbcModeHint}</small></button>
        <button type="button" aria-pressed={draft.mode === 'JNDI'} onClick={() => update('mode', 'JNDI')}><ServerCog /><strong>JNDI</strong><small>{c.jndiModeHint}</small></button>
      </fieldset>
      {draft.mode === 'JDBC' ? <div className="topology-form topology-form--grid">
        <label className="topology-field"><span>{c.host} *</span><input required value={draft.host} onChange={(e) => update('host', e.target.value)} autoComplete="off" /></label>
        <label className="topology-field"><span>{c.port} *</span><input required type="number" min="1" max="65535" value={draft.port} onChange={(e) => update('port', e.target.value)} /></label>
        <fieldset className="topology-segment topology-field--wide"><legend>{c.connectIdentifier}</legend><button type="button" aria-pressed={draft.identifierType === 'SERVICE_NAME'} onClick={() => update('identifierType', 'SERVICE_NAME')}>{c.serviceName}</button><button type="button" aria-pressed={draft.identifierType === 'SID'} onClick={() => update('identifierType', 'SID')}>{c.sid}</button></fieldset>
        <label className="topology-field"><span>{draft.identifierType === 'SERVICE_NAME' ? c.serviceName : c.sid} *</span><input required value={draft.identifier} onChange={(e) => update('identifier', e.target.value)} /></label>
        <label className="topology-field"><span>{c.credentialSecret} *</span><input required value={draft.credentialReferencePath} onChange={(e) => update('credentialReferencePath', e.target.value.toUpperCase())} placeholder="AKIS_ORACLE_SKY_CREDENTIAL" autoComplete="off" /></label>
        <p className="topology-security-note topology-field--wide"><ShieldCheck />{c.noSecretValues}</p>
      </div> : <div className="topology-form"><label className="topology-field"><span>{c.jndiName} *</span><input required value={draft.jndiName} onChange={(e) => update('jndiName', e.target.value)} placeholder="java:comp/env/jdbc/OracleMain" /></label><p className="topology-security-note"><CircleAlert />{c.localJndiOnly}</p></div>}
    </section>
    <section className="topology-form-section">
      <header><span>4</span><div><h3>{c.advancedSettings}</h3></div></header>
      <fieldset className="topology-timeout-grid"><legend>{c.executionPolicy}</legend>
        <label className="topology-field"><span>{c.connectTimeout}</span><input required type="number" min="1000" max="120000" value={draft.connectTimeoutMs} onChange={(e) => update('connectTimeoutMs', e.target.value)} /></label>
        <label className="topology-field"><span>{c.readTimeout}</span><input required type="number" min="1000" max="300000" value={draft.readTimeoutMs} onChange={(e) => update('readTimeoutMs', e.target.value)} /></label>
        <label className="topology-field"><span>{c.networkTimeout}</span><input required type="number" min="1000" max="300000" value={draft.networkTimeoutMs} onChange={(e) => update('networkTimeoutMs', e.target.value)} /></label>
        <label className="topology-field"><span>{c.queryTimeout}</span><input required type="number" min="1" max="3600" value={draft.queryTimeoutSeconds} onChange={(e) => update('queryTimeoutSeconds', e.target.value)} /></label>
      </fieldset>
    </section>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    {testResult && tested && <div className="topology-test-success" role="status"><CheckCircle2 /><div><strong>{c.draftTestPassed}</strong><small>{testResult.databaseProduct} {testResult.databaseVersion} · {testResult.driverName} {testResult.driverVersion}</small></div></div>}
    <div className="topology-form-actions topology-form-actions--sticky">
      <span className="topology-test-requirement">{tested ? c.draftTestPassed : c.testBeforeSave}</span>
      <button className="topology-button topology-button--quiet" type="button" onClick={onClose}>{c.cancel}</button>
      <button className="topology-button topology-button--test" type="button" onClick={() => void test()} disabled={Boolean(busy)}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <ShieldCheck />}{busy === 'test' ? c.testing : c.testDraftConnection}</button>
      <button className="topology-button" type="submit" disabled={Boolean(busy) || !tested}>{busy === 'save' ? <LoaderCircle className="is-spinning" /> : <Save />}{busy === 'save' ? c.creating : c.saveConnection}</button>
    </div>
  </form>
}
