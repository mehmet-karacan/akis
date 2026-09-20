import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, PageHeader } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi, type Connection, type PhysicalSchema, type SchemaBinding } from '../topology/api'
import '../schemas/schemas.css'
import './connections.css'
import { PhysicalSchemaManager } from './PhysicalSchemaManager'

export function PhysicalSchemasPage() {
  const { connectionUuid = '' } = useParams(); const projectUuid = useCurrentProjectUuid(); const { t } = useTranslation(); const { can } = useProjectAccess(); const canManage = can('BAGLANTI_YONET')
  const [connection, setConnection] = useState<Connection | null>(null); const [items, setItems] = useState<PhysicalSchema[]>([]); const [bindings, setBindings] = useState<SchemaBinding[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const load = useCallback(async () => { setLoading(true); setError(''); try { const [nextConnection, nextItems, nextBindings] = await Promise.all([topologyApi.getConnection(projectUuid, connectionUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listBindings(projectUuid)]); setConnection(nextConnection); setItems(nextItems.filter((item) => item.connectionUuid === connectionUuid)); setBindings(nextBindings) } catch { setError(t('common.loadError')) } finally { setLoading(false) } }, [connectionUuid, projectUuid, t])
  useEffect(() => { void load() }, [load])
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (!connection) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  return <section className="page-stack schema-page"><Link className="connection-back-link" to={`/projects/${projectUuid}/connections/${connectionUuid}`}><ArrowLeft size={16} />{connection.name}</Link><PageHeader title={t('schemas.physicalTitle')} description={t('schemas.physicalDescription', { name: connection.name })} eyebrow={connection.code} />{error ? <div className="error-banner" role="alert">{error}</div> : null}<PhysicalSchemaManager projectUuid={projectUuid} connection={connection} items={items} bindings={bindings} canManage={canManage} onChanged={load} /></section>
}
