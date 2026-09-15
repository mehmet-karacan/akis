import { Database, Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { AsyncState, Button } from '../../core/ui'
import { topologyApi, type ConnectionVersion, type PhysicalSchema } from '../topology/api'
import '../schemas/schemas.css'

export function PhysicalSchemaManager({ projectUuid, connectionUuid, version, items, canManage, onChanged }: {
  projectUuid: string
  connectionUuid: string
  version?: ConnectionVersion
  items: PhysicalSchema[]
  canManage: boolean
  onChanged: () => Promise<void>
}) {
  const { t } = useTranslation()
  const [available, setAvailable] = useState<string[]>([])
  const [schema, setSchema] = useState('')
  const [discovering, setDiscovering] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const usable = version?.lifecycleStatus === 'ACTIVE' || version?.lifecycleStatus === 'TESTED'

  async function discover() {
    if (!version || !usable) return
    setDiscovering(true); setError('')
    try { setAvailable(await topologyApi.listOracleSchemas(projectUuid, connectionUuid, version.uuid)) }
    catch { setError(t('schemas.schemaDiscoveryFailed')) }
    finally { setDiscovering(false) }
  }

  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      await topologyApi.createPhysicalSchema(projectUuid, { connectionUuid, schema: schema.trim().toUpperCase() })
      setSchema(''); await onChanged()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  return <div className="physical-schema-manager">
    {canManage && <form className="physical-schema-inline-form" onSubmit={(event) => void create(event)}>
      <label><span>{t('schemas.oracleUser')}</span><input list={`oracle-schema-options-${connectionUuid}`} value={schema} onChange={(event) => setSchema(event.target.value)} required placeholder="INNOVA_ODI" /></label>
      <datalist id={`oracle-schema-options-${connectionUuid}`}>{available.map((item) => <option key={item} value={item} />)}</datalist>
      <Button type="button" icon={<Database size={14} />} onClick={() => void discover()} busy={discovering} disabled={!usable}>{t('schemas.loadUsers')}</Button>
      <Button type="submit" tone="primary" icon={<Plus size={14} />} busy={busy} disabled={!schema.trim()}>{t('schemas.addPhysical')}</Button>
    </form>}
    {error && <div className="error-banner" role="alert">{error}</div>}
    {!usable && canManage && <p className="form-note">{t('schemas.testRequired')}</p>}
    {items.length ? <ul className="physical-schema-list">{items.map((item) => <li key={item.uuid}><span><Database size={15} /><strong>{item.name}</strong></span><code>{item.schemaReference}</code></li>)}</ul> : <AsyncState state="empty" compact title={t('schemas.noPhysical')} />}
  </div>
}
