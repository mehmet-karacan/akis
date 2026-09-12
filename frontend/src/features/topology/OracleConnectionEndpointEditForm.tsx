import { useMemo, useState, type FormEvent } from 'react'
import { CheckCircle2, CircleAlert, LoaderCircle, Save, ShieldCheck } from 'lucide-react'
import { topologyApi, type ConnectionVersion, type DraftConnectionTestResult } from './api'
import { toOracleConnectionInput, validateOracleConnectionInput, type ConnectionVersionDraft } from './connectionVersionModel'
import type { getTopologyCopy } from './copy'
import './topology.css'

interface Props {
  projectUuid: string
  connectionUuid: string
  version: ConnectionVersion
  copy: ReturnType<typeof getTopologyCopy>
  onSaved(): Promise<void>
}

function draftFrom(version: ConnectionVersion): ConnectionVersionDraft {
  const policy = (version.policy ?? {}) as Partial<Record<'connectTimeoutMs' | 'readTimeoutMs' | 'networkTimeoutMs' | 'queryTimeoutSeconds', number>>
  return {
    mode: version.mode,
    host: version.host ?? '', port: String(version.port ?? 1521),
    identifierType: version.sid ? 'SID' : 'SERVICE_NAME', identifier: version.sid ?? version.serviceName ?? '',
    transport: 'TCP', credentialReferencePath: '', username: version.username ?? '', password: '',
    jndiName: version.jndiName ?? '', connectTimeoutMs: String(policy.connectTimeoutMs ?? 10000),
    readTimeoutMs: String(policy.readTimeoutMs ?? 30000), networkTimeoutMs: String(policy.networkTimeoutMs ?? 30000),
    queryTimeoutSeconds: String(policy.queryTimeoutSeconds ?? 300),
  }
}

const fingerprint = (draft: ConnectionVersionDraft) => JSON.stringify(draft)

export function OracleConnectionEndpointEditForm({ projectUuid, connectionUuid, version, copy: c, onSaved }: Props) {
  const [draft, setDraft] = useState(() => draftFrom(version))
  const [busy, setBusy] = useState<'test' | 'save' | ''>('')
  const [error, setError] = useState('')
  const [testedFingerprint, setTestedFingerprint] = useState('')
  const [testResult, setTestResult] = useState<DraftConnectionTestResult | null>(null)
  const currentFingerprint = useMemo(() => fingerprint(draft), [draft])
  const tested = Boolean(testResult?.connected && testedFingerprint === currentFingerprint)
  const update = <K extends keyof ConnectionVersionDraft>(key: K, value: ConnectionVersionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value })); setTestResult(null); setTestedFingerprint(''); setError('')
  }
  const valid = () => {
    if (validateOracleConnectionInput(draft).length) { setError(c.invalidConnectionFields); return false }
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
      await topologyApi.createVersion(projectUuid, connectionUuid, toOracleConnectionInput(draft))
      await onSaved()
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.saveFailed) }
    finally { setBusy('') }
  }
  return <form className="topology-form topology-endpoint-edit" onSubmit={(event) => void save(event)}>
    <h3>{c.oracleConnectionDetails}</h3>
    <label className="topology-field"><span>{c.connectionMode} *</span><select value={draft.mode} onChange={(event) => update('mode', event.target.value as ConnectionVersionDraft['mode'])}><option value="JDBC">JDBC</option><option value="JNDI">JNDI</option></select></label>
    {draft.mode === 'JDBC' ? <>
      <div className="topology-form-row"><label className="topology-field"><span>{c.host} *</span><input required value={draft.host} onChange={(e) => update('host', e.target.value)} /></label><label className="topology-field"><span>{c.port} *</span><input required type="number" min="1" max="65535" value={draft.port} onChange={(e) => update('port', e.target.value)} /></label></div>
      <div className="topology-form-row"><label className="topology-field"><span>{c.connectionMethod} *</span><select value={draft.identifierType} onChange={(e) => update('identifierType', e.target.value as ConnectionVersionDraft['identifierType'])}><option value="SERVICE_NAME">{c.serviceName}</option><option value="SID">{c.sid}</option></select></label><label className="topology-field"><span>{draft.identifierType === 'SID' ? c.sid : c.serviceName} *</span><input required value={draft.identifier} onChange={(e) => update('identifier', e.target.value)} /></label></div>
      <div className="topology-form-row"><label className="topology-field"><span>{c.username} *</span><input required value={draft.username} onChange={(e) => update('username', e.target.value)} autoComplete="username" /></label><label className="topology-field"><span>{c.password} *</span><input required type="password" value={draft.password} onChange={(e) => update('password', e.target.value)} autoComplete="new-password" /></label></div>
    </> : <label className="topology-field"><span>{c.jndiName} *</span><input required value={draft.jndiName} onChange={(e) => update('jndiName', e.target.value)} /></label>}
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    {tested && <div className="topology-test-success"><CheckCircle2 /><div><strong>{c.draftTestPassed}</strong><small>{testResult?.databaseProduct} {testResult?.databaseVersion}</small></div></div>}
    <div className="topology-form-actions topology-form-actions--right"><button className="topology-button topology-button--test" type="button" onClick={() => void test()} disabled={Boolean(busy)}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <ShieldCheck />}{busy === 'test' ? c.testing : c.testDraftConnection}</button><button className="topology-button" type="submit" disabled={Boolean(busy) || !tested}>{busy === 'save' ? <LoaderCircle className="is-spinning" /> : <Save />}{busy === 'save' ? c.saving : c.saveConnection}</button></div>
  </form>
}
