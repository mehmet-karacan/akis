import { ArrowLeft, Pencil, Plus, ShieldAlert, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AsyncState, Button, Dialog, PageHeader, StatusBadge } from '../../core/ui'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import { OracleConnectionEndpointEditForm } from '../topology/OracleConnectionEndpointEditForm'
import { topologyApi, type Connection, type ConnectionVersion, type LogicalSchema, type PhysicalSchema, type SchemaBinding } from '../topology/api'
import { getTopologyCopy } from '../topology/copy'
import { endpointLabel } from './catalog'
import './connections.css'

export function ConnectionDetailPage() {
  const { projectUuid = '', connectionUuid = '' } = useParams()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const copy = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  const [connection, setConnection] = useState<Connection | null>(null)
  const [versions, setVersions] = useState<ConnectionVersion[]>([])
  const [physical, setPhysical] = useState<PhysicalSchema[]>([])
  const [logical, setLogical] = useState<LogicalSchema[]>([])
  const [bindings, setBindings] = useState<SchemaBinding[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [dialog, setDialog] = useState<'identity' | 'revision' | 'delete' | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [nextConnection, nextVersions, nextPhysical, nextLogical, nextBindings] = await Promise.all([
        topologyApi.getConnection(projectUuid, connectionUuid), topologyApi.listVersions(projectUuid, connectionUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listLogicalSchemas(projectUuid), topologyApi.listBindings(projectUuid),
      ])
      setConnection(nextConnection); setVersions(nextVersions); setPhysical(nextPhysical.filter((item) => item.connectionUuid === connectionUuid)); setLogical(nextLogical); setBindings(nextBindings)
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [connectionUuid, projectUuid, t])
  useEffect(() => { void load() }, [load])

  const active = versions.find((version) => version.lifecycleStatus === 'ACTIVE') ?? versions[0]
  const lifecycleText = (status: ConnectionVersion['lifecycleStatus']) => status === 'ACTIVE' ? t('connections.active') : status === 'TESTED' ? t('connections.tested') : t('connections.draft')
  const physicalIds = new Set(physical.map((item) => item.uuid))
  const relatedLogical = new Set(bindings.filter((item) => physicalIds.has(item.physicalSchemaUuid)).map((item) => item.logicalSchemaUuid))

  async function updateIdentity(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!connection) return
    const data = new FormData(event.currentTarget); setBusy(true); setError('')
    try {
      await topologyApi.updateConnection(projectUuid, connectionUuid, { code: String(data.get('code') ?? '').trim().toUpperCase(), name: String(data.get('name') ?? '').trim(), description: String(data.get('description') ?? '').trim() || undefined, expectedVersion: connection.version })
      setDialog(null); await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }
  async function remove() {
    if (!connection) return; setBusy(true); setError('')
    try { await topologyApi.deleteConnection(projectUuid, connectionUuid, connection.version); navigate(`/projects/${projectUuid}/connections`, { replace: true }) }
    catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')); setBusy(false) }
  }

  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error && !connection) return <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} />
  if (!connection) return null
  return <section className="page-stack connection-detail-page">
    <Link className="connection-back-link" to={`/projects/${projectUuid}/connections`}><ArrowLeft size={16} />{t('connections.back')}</Link>
    <PageHeader title={connection.name} description={connection.description ?? t('common.noDescription')} eyebrow={`${connection.databaseType} · ${connection.code}`} actions={<><Button icon={<Pencil size={16} />} onClick={() => setDialog('identity')}>{t('connections.edit')}</Button><Button tone="danger" icon={<Trash2 size={16} />} onClick={() => setDialog('delete')}>{t('connections.delete')}</Button></>} />
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    <article className="connection-summary-card">
      <div className="connection-provider-mark"><DatabaseProviderIcon databaseType={connection.databaseType} /></div>
      <dl><div><dt>{t('connections.endpoint')}</dt><dd>{endpointLabel(active)}</dd></div><div><dt>{t('connections.activeRevision')}</dt><dd>{active ? <>r{active.versionNumber} <StatusBadge tone={active.lifecycleStatus === 'ACTIVE' ? 'success' : 'warning'}>{lifecycleText(active.lifecycleStatus)}</StatusBadge></> : '—'}</dd></div><div><dt>{t('connections.physical')}</dt><dd>{physical.length}</dd></div><div><dt>{t('connections.logical')}</dt><dd>{logical.filter((item) => relatedLogical.has(item.uuid)).length}</dd></div></dl>
    </article>
    <div className="connection-detail-tabs" role="navigation" aria-label={t('connections.detailNavigation')}><a href="#revisions">{t('connections.revisions')}</a><a href="#physical">{t('connections.physical')}</a><a href="#usage">{t('connections.usage')}</a></div>
    <section id="revisions" className="connection-detail-section"><header><div><h2>{t('connections.revisions')}</h2><p>{t('connections.revisionsHint')}</p></div><Button icon={<Plus size={16} />} onClick={() => setDialog('revision')}>{t('connections.newRevision')}</Button></header><div className="revision-list">{versions.map((version) => <Link key={version.uuid} to={`/projects/${projectUuid}/connections/${connectionUuid}/revisions/${version.uuid}`}><span><strong>r{version.versionNumber} · {version.mode}</strong><small>{endpointLabel(version)}</small></span><StatusBadge tone={version.lifecycleStatus === 'ACTIVE' ? 'success' : version.lifecycleStatus === 'TESTED' ? 'info' : 'warning'}>{lifecycleText(version.lifecycleStatus)}</StatusBadge></Link>)}</div></section>
    <section id="physical" className="connection-detail-section"><header><div><h2>{t('connections.physical')}</h2><p>{t('connections.physicalHint')}</p></div></header>{physical.length ? <ul className="physical-schema-list">{physical.map((schema) => <li key={schema.uuid}><strong>{schema.name}</strong><code>{schema.schemaReference}</code></li>)}</ul> : <AsyncState state="empty" compact title={t('connections.noPhysical')} />}</section>
    <section id="usage" className="connection-detail-section"><header><div><h2>{t('connections.usage')}</h2><p>{t('connections.usageHint')}</p></div></header><p>{t('connections.usageCount', { count: relatedLogical.size })}</p></section>
    <Dialog open={dialog === 'identity'} title={t('connections.edit')} closeLabel={t('common.close')} busy={busy} onClose={() => setDialog(null)} className="connection-edit-dialog"><form onSubmit={(event) => void updateIdentity(event)}><label>{t('connections.name')}<input name="name" defaultValue={connection.name} required /></label><label>{t('connections.code')}<input name="code" defaultValue={connection.code} pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required /></label><label>{t('connections.descriptionField')}<textarea name="description" defaultValue={connection.description ?? ''} rows={3} /></label><footer><Button type="button" onClick={() => setDialog(null)}>{t('common.cancel')}</Button><Button type="submit" tone="primary" busy={busy} busyLabel={t('connections.saving')}>{t('connections.save')}</Button></footer></form></Dialog>
    <Dialog open={dialog === 'revision'} title={t('connections.endpointRevision')} closeLabel={t('common.close')} onClose={() => setDialog(null)} className="connection-edit-dialog">{active ? <OracleConnectionEndpointEditForm projectUuid={projectUuid} connectionUuid={connectionUuid} version={active} copy={copy} onSaved={async () => { await load(); setDialog(null) }} /> : null}</Dialog>
    <Dialog open={dialog === 'delete'} title={t('connections.delete')} closeLabel={t('common.close')} busy={busy} onClose={() => setDialog(null)}><div className="connection-delete-impact"><ShieldAlert /><p>{t('connections.deleteWarning')}</p><dl><div><dt>{t('connections.physical')}</dt><dd>{physical.length}</dd></div><div><dt>{t('connections.logical')}</dt><dd>{relatedLogical.size}</dd></div></dl><footer><Button onClick={() => setDialog(null)}>{t('common.cancel')}</Button><Button tone="danger" busy={busy} busyLabel={t('connections.deleting')} onClick={() => void remove()}>{t('connections.confirmDelete')}</Button></footer></div></Dialog>
  </section>
}
