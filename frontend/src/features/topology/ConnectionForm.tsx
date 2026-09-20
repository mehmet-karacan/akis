import { Disclosure } from '../../core/ui/Disclosure'
import { Button as ActionButton } from '../../core/ui/Button'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input } from 'antd'
import { useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { CheckCircle2, CircleAlert, LoaderCircle, PlugZap, Save } from 'lucide-react'
import { topologyApi, type Connection, type ConnectionTestProbe } from './api'
import {
  DATABASE_TYPES, DEFAULT_PORTS, draftFromConnection, initialConnectionDraft, toConnectionRequest,
  validateConnectionDraft, type ConnectionDraft, type DatabaseType,
} from './connectionFormModel'
import { databaseProviderVisual } from './DatabaseProviderIcon'
import type { getTopologyCopy } from './copy'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { useTranslation } from 'react-i18next'
import './topology.css'

interface Props {
  projectUuid: string
  copy: ReturnType<typeof getTopologyCopy>
  /** When present the form edits this connection with PATCH; otherwise it creates a new one. */
  connection?: Connection
  readOnly?: boolean
  footerActions?: ReactNode
  onSaved(connection: Connection): Promise<void> | void
  onClose?(): void
}

const fingerprint = (draft: ConnectionDraft) => JSON.stringify(draft)

export function ConnectionForm({ projectUuid, copy: c, connection, readOnly = false, footerActions, onSaved, onClose }: Props) {
  const editing = Boolean(connection)
  const hasStoredPassword = Boolean(connection?.hasPassword)
  const [draft, setDraft] = useState<ConnectionDraft>(() => connection ? draftFromConnection(connection) : initialConnectionDraft)
  const [busy, setBusy] = useState<'test' | 'save' | ''>('')
  const [error, setError] = useState('')
  const [testedFingerprint, setTestedFingerprint] = useState('')
  const [probe, setProbe] = useState<ConnectionTestProbe | null>(null)
  const currentFingerprint = useMemo(() => fingerprint(draft), [draft])
  const tested = Boolean(probe?.connected && testedFingerprint === currentFingerprint)
  const oracle = draft.databaseType === 'ORACLE'
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')

  const update = <K extends keyof ConnectionDraft>(key: K, value: ConnectionDraft[K]) => {
    setDraft((current) => ({ ...current, [key]: value })); setProbe(null); setTestedFingerprint(''); setError('')
  }
  const changeProvider = (value: string) => {
    const databaseType = value as DatabaseType | ''
    setDraft((current) => ({
      ...current, databaseType,
      port: databaseType && (!current.port || Object.values(DEFAULT_PORTS).includes(current.port)) ? DEFAULT_PORTS[databaseType] : current.port,
      mode: databaseType === 'ORACLE' ? current.mode : 'JDBC',
    }))
    setProbe(null); setTestedFingerprint(''); setError('')
  }
  const valid = () => {
    if (validateConnectionDraft(draft, editing && hasStoredPassword).length) { setError(c.invalidConnectionFields); return false }
    return true
  }
  const test = async () => {
    if (!valid()) return
    setBusy('test'); setError('')
    try {
      const request = toConnectionRequest(draft)
      const result = editing && !request.password && draft.mode === 'JDBC' && connection
        ? await topologyApi.testConnection(projectUuid, connection.uuid).then((attempt) => {
          if (attempt.outcome !== 'PASSED') throw new Error(attempt.errorCode ?? c.connectionTestFailed)
          return attempt.probe ?? { connected: true, databaseProduct: '', databaseVersion: '', driverName: '', driverVersion: '' }
        })
        : await topologyApi.testDraftConnection(projectUuid, request)
      setProbe(result); setTestedFingerprint(currentFingerprint)
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.connectionTestFailed) }
    finally { setBusy('') }
  }
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (readOnly || !valid()) return
    setBusy('save'); setError('')
    try {
      const request = toConnectionRequest(draft)
      const saved = connection
        ? await topologyApi.updateConnection(projectUuid, connection.uuid, request)
        : await topologyApi.createConnection(projectUuid, request)
      notifyFeedback(tr ? 'Bağlantı kaydedildi.' : 'Connection saved.')
      await onSaved(saved)
      onClose?.()
    } catch (reason) { setError(reason instanceof Error ? reason.message : c.saveFailed) }
    finally { setBusy('') }
  }

  const identifierLabel = oracle ? (draft.identifierType === 'SID' ? c.sid : c.serviceName) : (tr ? 'Veritabanı Adı' : 'Database Name')
  const ready = Boolean(draft.databaseType)

  return <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void save(event)}>
    <fieldset disabled={readOnly || Boolean(busy)} className="connection-editor-fields"><legend className="sr-only">{c.connectionDefinition}</legend>
    <div className="topology-form topology-form--grid">
      <label className="topology-field topology-field--wide"><span>{c.provider} *</span>
        <FormSelect required value={draft.databaseType} onChange={(event) => changeProvider(event.target.value)} disabled={editing}>
          <option value="">{c.chooseProvider}</option>
          {DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}
        </FormSelect>
      </label>
      {ready && <>
        <label className="topology-field"><span>{c.name} *</span><Input required value={draft.name} onChange={(e) => update('name', e.target.value)} /></label>
        <label className="topology-field"><span>{c.code} *</span><Input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={draft.code} onChange={(e) => update('code', e.target.value.toUpperCase())} /></label>
        {oracle && <label className="topology-field"><span>{c.connectionMode} *</span><FormSelect value={draft.mode} onChange={(e) => update('mode', e.target.value as ConnectionDraft['mode'])}><option value="JDBC">JDBC</option><option value="JNDI">JNDI</option></FormSelect></label>}
        {draft.mode === 'JDBC' ? <>
          <label className="topology-field"><span>{c.host} *</span><Input required value={draft.host} onChange={(e) => update('host', e.target.value)} placeholder="10.0.0.10" autoComplete="off" /></label>
          <label className="topology-field"><span>{c.port} *</span><Input required type="number" min="1" max="65535" value={draft.port} onChange={(e) => update('port', e.target.value)} /></label>
          {oracle && <label className="topology-field"><span>{c.connectionMethod} *</span><FormSelect value={draft.identifierType} onChange={(e) => update('identifierType', e.target.value as ConnectionDraft['identifierType'])}><option value="SERVICE_NAME">{c.serviceName}</option><option value="SID">{c.sid}</option></FormSelect></label>}
          <label className="topology-field"><span>{identifierLabel} *</span><Input required value={draft.identifier} onChange={(e) => update('identifier', e.target.value)} /></label>
          <label className="topology-field"><span>{c.username} *</span><Input required value={draft.username} onChange={(e) => update('username', e.target.value)} autoComplete="username" /></label>
          <label className="topology-field"><span>{c.password} *</span><Input.Password required={!(editing && hasStoredPassword)} value={draft.password} onChange={(e) => update('password', e.target.value)} autoComplete="new-password" placeholder={editing && hasStoredPassword ? '••••••••' : undefined} /></label>
        </> : <label className="topology-field topology-field--wide"><span>{c.jndiName} *</span><Input required value={draft.jndiName} onChange={(e) => update('jndiName', e.target.value)} placeholder="java:comp/env/jdbc/OracleMain" /></label>}
        <label className="topology-field topology-field--wide"><span>{c.description} ({c.optional.toLowerCase()})</span><Input.TextArea rows={2} value={draft.description} onChange={(e) => update('description', e.target.value)} /></label>
        <Disclosure className="topology-advanced topology-field--wide"><summary>{c.advancedSettings}</summary><div className="topology-timeout-grid">
          {draft.mode === 'JDBC' && <label className="topology-field"><span>{tr ? 'JDBC URL Eki' : 'JDBC URL Extra'}</span><Input value={draft.jdbcUrlExtra} onChange={(e) => update('jdbcUrlExtra', e.target.value)} placeholder="?sslmode=require" /></label>}
          <label className="topology-field"><span>{tr ? 'Getirme Boyutu' : 'Fetch Size'}</span><Input type="number" min="1" max="10000" value={draft.fetchSize} onChange={(e) => update('fetchSize', e.target.value)} /></label>
          <label className="topology-field"><span>{tr ? 'Toplu Güncelleme Boyutu' : 'Batch Update Size'}</span><Input type="number" min="1" max="10000" value={draft.batchSize} onChange={(e) => update('batchSize', e.target.value)} /></label>
          <label className="topology-field"><span>{c.connectTimeout}</span><Input type="number" min="1000" max="120000" value={draft.connectTimeoutMs} onChange={(e) => update('connectTimeoutMs', e.target.value)} /></label>
          <label className="topology-field"><span>{c.readTimeout}</span><Input type="number" min="1000" max="300000" value={draft.readTimeoutMs} onChange={(e) => update('readTimeoutMs', e.target.value)} /></label>
          <label className="topology-field"><span>{c.queryTimeout}</span><Input type="number" min="1" max="3600" value={draft.queryTimeoutSeconds} onChange={(e) => update('queryTimeoutSeconds', e.target.value)} /></label>
          <label className="topology-field topology-field--wide"><span>{tr ? 'Bağlantı Sonrası SQL' : 'On Connect SQL'}</span><Input.TextArea rows={2} value={draft.onConnectSql} onChange={(e) => update('onConnectSql', e.target.value)} /></label>
          <label className="topology-field topology-field--wide"><span>{tr ? 'Kapanış Öncesi SQL' : 'On Disconnect SQL'}</span><Input.TextArea rows={2} value={draft.onDisconnectSql} onChange={(e) => update('onDisconnectSql', e.target.value)} /></label>
        </div></Disclosure>
      </>}
    </div>
    </fieldset>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    {tested && probe && <div className="topology-test-success" role="status"><CheckCircle2 /><div><strong>{c.draftTestPassed}</strong><small>{probe.databaseProduct} {probe.databaseVersion}</small></div></div>}
    {!readOnly && <footer className={`topology-form-actions${editing ? ' topology-form-actions--right' : ''}`}>
      {footerActions}
      {onClose && <ActionButton tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={onClose}>{c.cancel}</ActionButton>}
      {ready && <ActionButton tone="ghost" className="topology-button topology-button--test" type="button" onClick={() => void test()} disabled={Boolean(busy)}>{busy === 'test' ? <LoaderCircle className="is-spinning" /> : <PlugZap />}{busy === 'test' ? c.testing : c.testDraftConnection}</ActionButton>}
      {ready && <ActionButton tone="primary" className="topology-button" type="submit" disabled={Boolean(busy)}>{busy === 'save' ? <LoaderCircle className="is-spinning" /> : <Save />}{busy === 'save' ? (editing ? c.saving : c.creating) : c.saveConnection}</ActionButton>}
    </footer>}
  </form>
}
