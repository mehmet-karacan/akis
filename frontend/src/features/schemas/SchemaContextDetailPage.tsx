import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { AsyncState, PageHeader } from '../../core/ui'
import { topologyApi, type Connection, type Environment, type LogicalSchema, type PhysicalSchema, type SchemaBinding } from '../topology/api'
import './schemas.css'

function SchemaContextDetailPage({ kind }: { kind: 'logical' | 'environment' }) {
  const params = useParams(); const projectUuid = params.projectUuid ?? ''; const uuid = kind === 'logical' ? params.logicalSchemaUuid ?? '' : params.environmentUuid ?? ''; const { t } = useTranslation()
  const [logical, setLogical] = useState<LogicalSchema[]>([]); const [environments, setEnvironments] = useState<Environment[]>([]); const [physical, setPhysical] = useState<PhysicalSchema[]>([]); const [connections, setConnections] = useState<Connection[]>([]); const [bindings, setBindings] = useState<SchemaBinding[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const load = useCallback(async () => { setLoading(true); setError(''); try { const [nextLogical, nextEnvironments, nextPhysical, nextConnections, nextBindings] = await Promise.all([topologyApi.listLogicalSchemas(projectUuid), topologyApi.listEnvironments(projectUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listConnections(projectUuid), topologyApi.listBindings(projectUuid)]); setLogical(nextLogical); setEnvironments(nextEnvironments); setPhysical(nextPhysical); setConnections(nextConnections); setBindings(nextBindings) } catch { setError(t('common.loadError')) } finally { setLoading(false) } }, [projectUuid, t])
  useEffect(() => { void load() }, [load])
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  const definition = kind === 'logical' ? logical.find((item) => item.uuid === uuid) : environments.find((item) => item.uuid === uuid)
  if (error || !definition) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const rows = bindings.filter((item) => kind === 'logical' ? item.logicalSchemaUuid === uuid : item.environmentUuid === uuid)
  const backPath = kind === 'logical' ? 'logical-schemas' : 'environments'
  return <section className="page-stack schema-page"><Link className="connection-back-link" to={`/projects/${projectUuid}/${backPath}`}><ArrowLeft size={16} />{kind === 'logical' ? t('schemas.logicalTitle') : t('schemas.environmentsTitle')}</Link><PageHeader eyebrow={definition.code} title={definition.name} description={kind === 'logical' ? (definition as LogicalSchema).description ?? t('schemas.logicalDetailHint') : t('schemas.environmentDetailHint')} /><section className="connection-detail-section"><h2>{t('schemas.resolvedTargets')}</h2>{rows.length === 0 ? <AsyncState state="empty" compact title={t('schemas.notMapped')} /> : <div className="schema-list-table"><table><thead><tr><th>{kind === 'logical' ? t('schemas.environment') : t('schemas.logicalSchema')}</th><th>{t('connections.connection')}</th><th>{t('schemas.physicalSchema')}</th></tr></thead><tbody>{rows.map((binding) => { const schema = physical.find((item) => item.uuid === binding.physicalSchemaUuid); const connection = connections.find((item) => item.uuid === schema?.connectionUuid); const context = kind === 'logical' ? environments.find((item) => item.uuid === binding.environmentUuid) : logical.find((item) => item.uuid === binding.logicalSchemaUuid); return <tr key={binding.uuid}><td><strong>{context?.name ?? '—'}</strong></td><td>{connection?.name ?? '—'}</td><td><code>{schema?.schemaReference ?? '—'}</code></td></tr> })}</tbody></table></div>}</section></section>
}

export function LogicalSchemaDetailPage() { return <SchemaContextDetailPage kind="logical" /> }
export function EnvironmentDetailPage() { return <SchemaContextDetailPage kind="environment" /> }
