import { ArrowLeft, Cable, Database, FileText, GitBranch, ListTree, ShieldAlert, Trash2 } from 'lucide-react'
import { Tabs } from 'antd'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, SummaryStrip } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { ConnectionForm } from '../topology/ConnectionForm'
import {
  topologyApi,
  type Connection,
  type ConnectionDependency,
  type LogicalSchema,
  type PhysicalSchema,
  type SchemaBinding,
} from '../topology/api'
import { getTopologyCopy } from '../topology/copy'
import { PhysicalSchemaManager } from './PhysicalSchemaManager'
import './connections.css'

export function ConnectionDetailPage({ selectedUuid, onDeleted, onChanged }: { selectedUuid?: string; onDeleted?: () => void; onChanged?: () => void } = {}) {
  const { connectionUuid: routeUuid = '' } = useParams(); const connectionUuid = selectedUuid ?? routeUuid; const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const { can } = useProjectAccess()
  const canWrite = can('BAGLANTI_YONET')
  const tr = i18n.language.startsWith('tr')
  const navigate = useNavigate()
  const copy = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  const [connection, setConnection] = useState<Connection | null>(null)
  const [physical, setPhysical] = useState<PhysicalSchema[]>([])
  const [logical, setLogical] = useState<LogicalSchema[]>([])
  const [bindings, setBindings] = useState<SchemaBinding[]>([])
  const [dependencies, setDependencies] = useState<ConnectionDependency[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [dialog, setDialog] = useState<'delete' | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextConnection, nextPhysical, nextLogical, nextBindings, nextDependencies] = await Promise.all([
        topologyApi.getConnection(projectUuid, connectionUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listBindings(projectUuid),
        topologyApi.listConnectionDependencies(projectUuid, connectionUuid),
      ])
      setConnection(nextConnection)
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

  const physicalIds = new Set(physical.map((item) => item.uuid))
  const relatedLogical = new Set(bindings.filter((item) => physicalIds.has(item.physicalSchemaUuid)).map((item) => item.logicalSchemaUuid))

  async function remove() {
    if (!connection) return
    setBusy(true)
    setError('')
    try {
      await topologyApi.deleteConnection(projectUuid, connectionUuid)
      if (onDeleted) onDeleted(); else navigate(`/project/connections`, { replace: true })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('common.saveError'))
      setBusy(false)
    }
  }

  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error && !connection) return <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} />
  if (!connection) return null

  const deleteBlocked = dependencies.length > 0 || physical.length > 0
  const deleteImpact = <><p>{deleteBlocked ? t('connections.deleteBlocked') : t('connections.deleteWarning')}</p><dl><div><dt>{t('connections.physical')}</dt><dd>{physical.length}</dd></div><div><dt>{t('connections.logical')}</dt><dd>{dependencies.length}</dd></div></dl>{dependencies.length ? <ul>{dependencies.map((item) => <li key={item.uuid}><strong>{item.name}</strong><small>{t('connections.logical')}</small></li>)}</ul> : null}</>
  const deleteFooter = <footer><Button disabled={busy} onClick={() => setDialog(null)}>{t('common.cancel')}</Button><Button tone="danger" busy={busy} disabled={deleteBlocked} busyLabel={t('connections.deleting')} onClick={() => void remove()}>{t('connections.confirmDelete')}</Button></footer>

  if (selectedUuid && dialog === 'delete') return <section className="connection-delete-impact"><ShieldAlert /><h3>{t('connections.delete')}</h3>{deleteImpact}{error && <p role="alert">{error}</p>}{deleteFooter}</section>
  return <section className="page-stack connection-detail-page">
    {!selectedUuid && <Link className="connection-back-link" to={`/project/connections`}><ArrowLeft size={16} />{t('connections.back')}</Link>}
    {!selectedUuid && <PageHeader
      icon={<Cable />}
      title={connection.name}
      description={connection.description ?? t('common.noDescription')}
      eyebrow={`${connection.databaseType} · ${connection.code}`}
    />}
    {selectedUuid && <p className="connection-panel-description"><FileText size={16} aria-hidden="true" />{connection.description ?? t('common.noDescription')}</p>}
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    <SummaryStrip ariaLabel={t('connections.details')} items={[
      { label: t('connections.physical'), value: physical.length, icon: <Database />, tone: 'teal' },
      { label: t('connections.logical'), value: logical.filter(item => relatedLogical.has(item.uuid)).length, icon: <GitBranch />, tone: 'neutral' },
    ]} />
    <Tabs items={[{ key: 'details', label: <span className="connection-tab-label connection-tab-definition"><Cable size={16} />{tr ? 'Bağlantı Tanımı' : 'Connection Definition'}</span>, children: <section id="details" className="connection-detail-section">
      <ConnectionForm key={`${connection.uuid}:${connection.updatedAt ?? ''}`} projectUuid={projectUuid} connection={connection} copy={copy} readOnly={!canWrite} footerActions={canWrite ? <Button tone="danger" icon={<Trash2 size={16} />} onClick={() => setDialog('delete')}>{t('connections.delete')}</Button> : undefined} onSaved={async () => { await load(); onChanged?.() }} />
    </section> }, { key: 'physical', label: <span className="connection-tab-label connection-tab-physical"><ListTree size={16} />{tr ? 'Fiziksel Şemalar' : 'Physical Schemas'}</span>, children: <section id="physical" className="connection-detail-section"><p>{t('connections.physicalHint')}</p><PhysicalSchemaManager projectUuid={projectUuid} connection={connection} items={physical} bindings={bindings} canManage={canWrite} onChanged={async () => { await load(); onChanged?.() }} /></section> }]} />
    <Dialog open={!selectedUuid && dialog === 'delete'} title={t('connections.delete')} closeLabel={t('common.close')} busy={busy} onClose={() => setDialog(null)}><div className="connection-delete-impact"><ShieldAlert />{deleteImpact}{deleteFooter}</div></Dialog>
  </section>
}
