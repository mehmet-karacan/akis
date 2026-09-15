import { useMemo, useState, type ReactNode, type FormEvent } from 'react'
import { CheckCircle2, CircleAlert, LoaderCircle, Save, ShieldCheck } from 'lucide-react'
import { topologyApi, type Connection, type ConnectionVersion, type DraftConnectionTestResult } from './api'
import { toOracleConnectionInput, validateOracleConnectionInput, type ConnectionVersionDraft } from './connectionVersionModel'
import type { getTopologyCopy } from './copy'
import { ConnectionTestButton } from '../connections/ConnectionTestButton'
import './topology.css'

interface Props {
  projectUuid: string
  connectionUuid: string
  connection: Connection
  version: ConnectionVersion
  copy: ReturnType<typeof getTopologyCopy>
  readOnly?: boolean
  footerActions?: ReactNode
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

export function OracleConnectionEndpointEditForm({ projectUuid, connectionUuid, connection, version, copy: c, onSaved, readOnly = false, footerActions }: Props) {
  const [name, setName] = useState(connection.name)
  const [code, setCode] = useState(connection.code)
  const [description, setDescription] = useState(connection.description ?? '')
  const [draft, setDraft] = useState(() => draftFrom(version))
  const [busy, setBusy] = useState<'test' | 'save' | ''>('')
  const [error, setError] = useState('')
  const [testedFingerprint, setTestedFingerprint] = useState('')
  const [testResult, setTestResult] = useState<DraftConnectionTestResult | null>(null)
  const currentFingerprint = useMemo(() => fingerprint(draft), [draft])
  const endpointChanged = currentFingerprint !== fingerprint(draftFrom(version))
  const tested = Boolean(testResult?.connected && testedFingerprint === currentFingerprint)
  const update = <K extends keyof ConnectionVersionDraft>(key: K, value: ConnectionVersionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value })); setTestResult(null); setTestedFingerprint(''); setError('')
  }
  const valid = () => {
    if (!name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code)
        || validateOracleConnectionInput(draft).length) { setError(c.invalidConnectionFields); return false }
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
    if (readOnly) return
    if (!name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code)) { setError(c.invalidConnectionFields); return }
    if (endpointChanged && !valid()) return
    setBusy('save'); setError('')
    try {
      if (endpointChanged) {
        const next = await topologyApi.createVersion(projectUuid, connectionUuid, toOracleConnectionInput(draft))
        if (tested && next.mode === 'JDBC') await topologyApi.makeConnectionCurrent(projectUuid, connectionUuid, next.uuid)
      }
      await topologyApi.updateConnection(projectUuid, connectionUuid, {
        code: code.trim().toUpperCase(),
        name: name.trim(),
        description: description.trim() || undefined,
        expectedVersion: connection.version,
      })
      await onSaved()
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.saveFailed) }
    finally { setBusy('') }
  }
  return <form className="topology-form topology-endpoint-edit" onSubmit={(event) => void save(event)}>
    <fieldset disabled={readOnly || Boolean(busy)} className="connection-editor-fields"><h3>{c.connectionDefinition}</h3>
    <div className="topology-form-row"><label className="topology-field"><span>{c.name} *</span><input required value={name} onChange={(event) => setName(event.target.value)} /></label><label className="topology-field"><span>{c.code} *</span><input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={code} onChange={(event) => setCode(event.target.value.toUpperCase())} /></label></div>
    <label className="topology-field"><span>{c.description}</span><textarea rows={2} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
    <h3>{c.oracleConnectionDetails}</h3>
    <label className="topology-field"><span>{c.connectionMode} *</span><select value={draft.mode} onChange={(event) => update('mode', event.target.value as ConnectionVersionDraft['mode'])}><option value="JDBC">JDBC</option><option value="JNDI">JNDI</option></select></label>
    {draft.mode === 'JDBC' ? <>
      <div className="topology-form-row"><label className="topology-field"><span>{c.host} *</span><input required value={draft.host} onChange={(e) => update('host', e.target.value)} /></label><label className="topology-field"><span>{c.port} *</span><input required type="number" min="1" max="65535" value={draft.port} onChange={(e) => update('port', e.target.value)} /></label></div>
      <div className="topology-form-row"><label className="topology-field"><span>{c.connectionMethod} *</span><select value={draft.identifierType} onChange={(e) => update('identifierType', e.target.value as ConnectionVersionDraft['identifierType'])}><option value="SERVICE_NAME">{c.serviceName}</option><option value="SID">{c.sid}</option></select></label><label className="topology-field"><span>{draft.identifierType === 'SID' ? c.sid : c.serviceName} *</span><input required value={draft.identifier} onChange={(e) => update('identifier', e.target.value)} /></label></div>
      <div className="topology-form-row"><label className="topology-field"><span>{c.username} *</span><input required value={draft.username} onChange={(e) => update('username', e.target.value)} autoComplete="username" /></label><label className="topology-field"><span>{c.password} *</span><input required={endpointChanged} type="password" value={draft.password} onChange={(e) => update('password', e.target.value)} autoComplete="new-password" /></label></div>
    </> : <label className="topology-field"><span>{c.jndiName} *</span><input required value={draft.jndiName} onChange={(e) => update('jndiName', e.target.value)} /></label>}
    </fieldset>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    {tested && <div className="topology-test-success"><CheckCircle2 /><div><strong>{c.draftTestPassed}</strong><small>{testResult?.databaseProduct} {testResult?.databaseVersion}</small></div></div>}
    {!readOnly && <div className="topology-form-actions topology-form-actions--right">{footerActions}{!endpointChanged ? <ConnectionTestButton connectionUuid={connectionUuid} versionUuid={version.uuid} /> : <button className="topology-button topology-button--test" type="button" onClick={() => void test()} disabled={Boolean(busy)}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <ShieldCheck />}{busy === 'test' ? c.testing : c.testDraftConnection}</button>}<button className="topology-button" type="submit" disabled={Boolean(busy)}>{busy === 'save' ? <LoaderCircle className="is-spinning" /> : <Save />}{busy === 'save' ? c.saving : c.saveConnection}</button></div>}
  </form>
}
