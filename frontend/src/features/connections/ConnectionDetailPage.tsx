import { ArrowLeft, Pencil, Plus, ShieldAlert, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AsyncState, Button, Dialog, PageHeader, StatusBadge } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import { OracleConnectionEndpointEditForm } from '../topology/OracleConnectionEndpointEditForm'
import {
  topologyApi,
  type Connection,
  type ConnectionDependency,
  type ConnectionVersion,
  type LogicalSchema,
  type PhysicalSchema,
  type SchemaBinding,
} from '../topology/api'
import { getTopologyCopy } from '../topology/copy'
import { endpointLabel } from './catalog'
import './connections.css'

export function ConnectionDetailPage() {
  const { projectUuid = '', connectionUuid = '' } = useParams()
  const { t, i18n } = useTranslation()
  const { can } = useProjectAccess()
  const canWrite = can('BAGLANTI_YONET')
  const navigate = useNavigate()
  const copy = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  const [connection, setConnection] = useState<Connection | null>(null)
  const [configurations, setConfigurations] = useState<ConnectionVersion[]>([])
  const [physical, setPhysical] = useState<PhysicalSchema[]>([])
  const [logical, setLogical] = useState<LogicalSchema[]>([])
  const [bindings, setBindings] = useState<SchemaBinding[]>([])
  const [dependencies, setDependencies] = useState<ConnectionDependency[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [dialog, setDialog] = useState<'edit' | 'delete' | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextConnection, nextConfigurations, nextPhysical, nextLogical, nextBindings, nextDependencies] = await Promise.all([
        topologyApi.getConnection(projectUuid, connectionUuid),
        topologyApi.listVersions(projectUuid, connectionUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listBindings(projectUuid),
        topologyApi.listConnectionDependencies(projectUuid, connectionUuid),
      ])
      setConnection(nextConnection)
      setConfigurations(nextConfigurations)
      setPhysical(nextPhysical.filter((item) => item.connectionUuid === connectionUuid))
      setLogical(nextLogical)
      setBindings(nextBindings)
      setDependencies(nextDependencies)
    } catch {
      setError(t('common.loadError'))
    } finally {
      setLoading(false)
    }
  }, [connectionUuid, projectUuid, t])

  useEffect(() => { void load() }, [load])

  const current = configurations.find((item) => item.lifecycleStatus === 'ACTIVE') ?? configurations[0]
  const physicalIds = new Set(physical.map((item) => item.uuid))
  const relatedLogical = new Set(bindings.filter((item) => physicalIds.has(item.physicalSchemaUuid)).map((item) => item.logicalSchemaUuid))
  const ready = current?.lifecycleStatus === 'ACTIVE' || current?.lifecycleStatus === 'TESTED'

  async function remove() {
    if (!connection) return
    setBusy(true)
    setError('')
    try {
      await topologyApi.deleteConnection(projectUuid, connectionUuid, connection.version)
      navigate(`/projects/${projectUuid}/connections`, { replace: true })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('common.saveError'))
      setBusy(false)
    }
  }

  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error && !connection) return <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} />
  if (!connection) return null

  return <section className="page-stack connection-detail-page">
    <Link className="connection-back-link" to={`/projects/${projectUuid}/connections`}><ArrowLeft size={16} />{t('connections.back')}</Link>
    <PageHeader
      title={connection.name}
      description={connection.description ?? t('common.noDescription')}
      eyebrow={`${connection.databaseType} · ${connection.code}`}
      actions={canWrite ? <><Button icon={<Pencil size={16} />} onClick={() => setDialog('edit')}>{t('connections.edit')}</Button><Button tone="danger" icon={<Trash2 size={16} />} onClick={() => setDialog('delete')}>{t('connections.delete')}</Button></> : undefined}
    />
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    <article className="connection-summary-card">
      <div className="connection-provider-mark"><DatabaseProviderIcon databaseType={connection.databaseType} /></div>
      <dl>
        <div><dt>{t('connections.endpoint')}</dt><dd>{endpointLabel(current)}</dd></div>
        <div><dt>{t('connections.username')}</dt><dd>{current?.username ?? '—'}</dd></div>
        <div><dt>{t('connections.status')}</dt><dd><StatusBadge tone={ready ? 'success' : 'warning'}>{ready ? t('connections.ready') : t('connections.testRequired')}</StatusBadge></dd></div>
        <div><dt>{t('connections.physical')}</dt><dd>{physical.length}</dd></div>
        <div><dt>{t('connections.logical')}</dt><dd>{logical.filter((item) => relatedLogical.has(item.uuid)).length}</dd></div>
      </dl>
    </article>
    <div className="connection-detail-tabs" role="navigation" aria-label={t('connections.detailNavigation')}><a href="#details">{t('connections.details')}</a><a href="#physical">{t('connections.physical')}</a><a href="#usage">{t('connections.usage')}</a></div>
    <section id="details" className="connection-detail-section">
      <header><div><h2>{t('connections.details')}</h2><p>{t('connections.currentConnectionHint')}</p></div>{canWrite && current ? <Button icon={<Pencil size={16} />} onClick={() => setDialog('edit')}>{t('connections.editConnection')}</Button> : null}</header>
      {current ? <dl className="connection-current-details"><div><dt>{t('connections.mode')}</dt><dd>{current.mode}</dd></div><div><dt>{t('connections.endpoint')}</dt><dd>{endpointLabel(current)}</dd></div><div><dt>{t('connections.username')}</dt><dd>{current.username ?? '—'}</dd></div><div><dt>{t('connections.lastTest')}</dt><dd>{current.testedAt ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(current.testedAt)) : t('connections.notTested')}</dd></div></dl> : <AsyncState state="empty" compact title={t('connections.noConnectionInfo')} />}
    </section>
    <section id="physical" className="connection-detail-section"><header><div><h2>{t('connections.physical')}</h2><p>{t('connections.physicalHint')}</p></div>{canWrite && <Link className="button secondary" to={`/projects/${projectUuid}/connections/${connectionUuid}/physical-schemas`}><Plus size={16} />{t('connections.managePhysical')}</Link>}</header>{physical.length ? <ul className="physical-schema-list">{physical.map((schema) => <li key={schema.uuid}><strong>{schema.name}</strong><code>{schema.schemaReference}</code></li>)}</ul> : <AsyncState state="empty" compact title={t('connections.noPhysical')} />}</section>
    <section id="usage" className="connection-detail-section"><header><div><h2>{t('connections.usage')}</h2><p>{t('connections.usageHint')}</p></div></header><p>{t('connections.usageCount', { count: relatedLogical.size })}</p></section>
    <Dialog open={dialog === 'edit'} title={t('connections.editConnection')} closeLabel={t('common.close')} onClose={() => setDialog(null)} className="connection-edit-dialog">{current ? <OracleConnectionEndpointEditForm projectUuid={projectUuid} connectionUuid={connectionUuid} connection={connection} version={current} copy={copy} onSaved={async () => { await load(); setDialog(null) }} /> : null}</Dialog>
    <Dialog open={dialog === 'delete'} title={t('connections.delete')} closeLabel={t('common.close')} busy={busy} onClose={() => setDialog(null)}><div className="connection-delete-impact"><ShieldAlert /><p>{dependencies.length ? t('connections.deleteBlocked') : t('connections.deleteWarning')}</p><dl><div><dt>{t('connections.physical')}</dt><dd>{physical.length}</dd></div><div><dt>{t('connections.logical')}</dt><dd>{dependencies.length}</dd></div></dl>{dependencies.length ? <ul>{dependencies.map((item) => <li key={item.uuid}><strong>{item.name}</strong><small>{t('connections.logical')}</small></li>)}</ul> : null}<footer><Button onClick={() => setDialog(null)}>{t('common.cancel')}</Button><Button tone="danger" busy={busy} disabled={dependencies.length > 0} busyLabel={t('connections.deleting')} onClick={() => void remove()}>{t('connections.confirmDelete')}</Button></footer></div></Dialog>
  </section>
}
