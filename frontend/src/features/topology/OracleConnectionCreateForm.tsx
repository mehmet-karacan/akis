import { useMemo, useState, type FormEvent } from 'react'
import { CheckCircle2, CircleAlert, LoaderCircle, Save, ShieldCheck } from 'lucide-react'
import { topologyApi, type DraftConnectionTestResult } from './api'
import { initialConnectionVersionDraft, toOracleConnectionInput, validateOracleConnectionInput, type ConnectionVersionDraft } from './connectionVersionModel'
import type { getTopologyCopy } from './copy'
import './topology.css'

interface Props {
  projectUuid: string
  copy: ReturnType<typeof getTopologyCopy>
  onConnectionCreated(connectionUuid: string): Promise<void>
  onClose(): void
}

const fingerprint = (draft: ConnectionVersionDraft) => JSON.stringify(draft)

export function OracleConnectionCreateForm({ projectUuid, copy: c, onConnectionCreated, onClose }: Props) {
  const [provider, setProvider] = useState('')
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [draft, setDraft] = useState<ConnectionVersionDraft>(initialConnectionVersionDraft)
  const [busy, setBusy] = useState<'test' | 'save' | ''>('')
  const [error, setError] = useState('')
  const [testedFingerprint, setTestedFingerprint] = useState('')
  const [testResult, setTestResult] = useState<DraftConnectionTestResult | null>(null)
  const currentFingerprint = useMemo(() => fingerprint(draft), [draft])
  const tested = Boolean(testResult?.connected && testedFingerprint === currentFingerprint)

  const invalidate = () => { setTestResult(null); setTestedFingerprint(''); setError('') }
  const update = <K extends keyof ConnectionVersionDraft>(key: K, value: ConnectionVersionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value })); invalidate()
  }
  const valid = () => {
    if (provider !== 'ORACLE' || !name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code)
        || validateOracleConnectionInput(draft).length) {
      setError(c.invalidConnectionFields); return false
    }
    return true
  }
  const test = async () => {
    if (!valid()) return
    setBusy('test'); setError('')
    try {
      const result = await topologyApi.testOracleDraftConnection(projectUuid, toOracleConnectionInput(draft))
      setTestResult(result); setTestedFingerprint(currentFingerprint)
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.connectionTestFailed) }
    finally { setBusy('') }
  }
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!valid() || !tested) { setError(c.testBeforeSave); return }
    setBusy('save'); setError('')
    try {
      const input = toOracleConnectionInput(draft)
      const { credentials, ...initialVersion } = input
      const result = await topologyApi.createOracleConnection(projectUuid, {
        name: name.trim(), code: code.trim(), description: description.trim() || undefined,
        initialVersion, credentials,
      })
      await onConnectionCreated(result.connection.uuid); onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.saveFailed) }
    finally { setBusy('') }
  }

  return <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void save(event)}>
    <div className="topology-form topology-form--grid">
      <label className="topology-field topology-field--wide"><span>{c.provider} *</span>
        <select required value={provider} onChange={(event) => { setProvider(event.target.value); invalidate() }}>
          <option value="">{c.chooseProvider}</option><option value="ORACLE">Oracle</option>
        </select>
      </label>
      {provider === 'ORACLE' && <>
        <label className="topology-field"><span>{c.name} *</span><input required value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="topology-field"><span>{c.code} *</span><input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} /></label>
        <label className="topology-field"><span>{c.connectionMode} *</span><select value={draft.mode} onChange={(e) => update('mode', e.target.value as ConnectionVersionDraft['mode'])}><option value="JDBC">JDBC</option><option value="JNDI">JNDI</option></select></label>
        {draft.mode === 'JDBC' ? <>
          <label className="topology-field"><span>{c.host} *</span><input required value={draft.host} onChange={(e) => update('host', e.target.value)} placeholder="10.0.0.10" autoComplete="off" /></label>
          <label className="topology-field"><span>{c.port} *</span><input required type="number" min="1" max="65535" value={draft.port} onChange={(e) => update('port', e.target.value)} /></label>
          <label className="topology-field"><span>{c.connectionMethod} *</span><select value={draft.identifierType} onChange={(e) => update('identifierType', e.target.value as ConnectionVersionDraft['identifierType'])}><option value="SERVICE_NAME">{c.serviceName}</option><option value="SID">{c.sid}</option></select></label>
          <label className="topology-field"><span>{draft.identifierType === 'SERVICE_NAME' ? c.serviceName : c.sid} *</span><input required value={draft.identifier} onChange={(e) => update('identifier', e.target.value)} /></label>
          <label className="topology-field"><span>{c.username} *</span><input required value={draft.username} onChange={(e) => update('username', e.target.value)} autoComplete="username" /></label>
          <label className="topology-field"><span>{c.password} *</span><input required type="password" value={draft.password} onChange={(e) => update('password', e.target.value)} autoComplete="new-password" /></label>
        </> : <label className="topology-field topology-field--wide"><span>{c.jndiName} *</span><input required value={draft.jndiName} onChange={(e) => update('jndiName', e.target.value)} placeholder="java:comp/env/jdbc/OracleMain" /></label>}
        <label className="topology-field topology-field--wide"><span>{c.description} ({c.optional.toLowerCase()})</span><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <details className="topology-advanced topology-field--wide"><summary>{c.advancedSettings}</summary><div className="topology-timeout-grid">
          <label className="topology-field"><span>{c.connectTimeout}</span><input required type="number" min="1000" max="120000" value={draft.connectTimeoutMs} onChange={(e) => update('connectTimeoutMs', e.target.value)} /></label>
          <label className="topology-field"><span>{c.readTimeout}</span><input required type="number" min="1000" max="300000" value={draft.readTimeoutMs} onChange={(e) => update('readTimeoutMs', e.target.value)} /></label>
          <label className="topology-field"><span>{c.networkTimeout}</span><input required type="number" min="1000" max="300000" value={draft.networkTimeoutMs} onChange={(e) => update('networkTimeoutMs', e.target.value)} /></label>
          <label className="topology-field"><span>{c.queryTimeout}</span><input required type="number" min="1" max="3600" value={draft.queryTimeoutSeconds} onChange={(e) => update('queryTimeoutSeconds', e.target.value)} /></label>
        </div></details>
      </>}
    </div>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    {testResult && tested && <div className="topology-test-success" role="status"><CheckCircle2 /><div><strong>{c.draftTestPassed}</strong><small>{testResult.databaseProduct} {testResult.databaseVersion}</small></div></div>}
    <footer className="topology-form-actions">
      <button className="topology-button topology-button--quiet" type="button" onClick={onClose}>{c.cancel}</button>
      {provider === 'ORACLE' && <><button className="topology-button topology-button--test" type="button" onClick={() => void test()} disabled={Boolean(busy)}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <ShieldCheck />}{busy === 'test' ? c.testing : c.testDraftConnection}</button>
      <button className="topology-button" type="submit" disabled={Boolean(busy) || !tested}>{busy === 'save' ? <LoaderCircle className="is-spinning" /> : <Save />}{busy === 'save' ? c.creating : c.saveConnection}</button></>}
    </footer>
  </form>
}
