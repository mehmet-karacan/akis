import { Tag } from 'antd'
import { Cable, CheckCircle2, CircleAlert, Database, GitBranch, Link2, Plus, Workflow } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, RecordActionButton, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi, type Connection, type Environment, type PhysicalSchema } from '../topology/api'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { connectionStatusTagStyles } from '../connections/presentation'
import { buildLogicalSchemaCatalog, mappingLabel, mappingState, type LogicalSchemaCatalogItem } from './logicalCatalog'
import { LogicalSchemaForm } from './LogicalSchemaForm'
import { LogicalSchemaDetailDialog } from './LogicalSchemaDetailDialog'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import './schemas.css'

/** Same catalog layout as the connections screen: header + filter, summary strip, card/list/table records. */
export function LogicalSchemasPage() {
  const projectUuid = useCurrentProjectUuid()
  const [view, setView] = useCollectionView('akis:logical-schemas:view')
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [params, setParams] = useSearchParams()
  const { can } = useProjectAccess()
  const canManage = can('BAGLANTI_YONET')
  const [catalog, setCatalog] = useState<LogicalSchemaCatalogItem[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [physicalSchemas, setPhysicalSchemas] = useState<PhysicalSchema[]>([])
  const [connections, setConnections] = useState<Connection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [schemas, nextEnvironments, nextPhysical, nextConnections, bindings] = await Promise.all([
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listConnections(projectUuid),
        topologyApi.listBindings(projectUuid),
      ])
      setEnvironments(nextEnvironments); setPhysicalSchemas(nextPhysical); setConnections(nextConnections)
      setCatalog(buildLogicalSchemaCatalog(schemas, nextEnvironments, nextPhysical, nextConnections, bindings))
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])
  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})

  const filtered = useMemo(() => catalog
    .filter((item) => `${item.schema.name} ${item.schema.code} ${item.schema.databaseType ?? ''} ${item.schema.description ?? ''}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => a.schema.name.localeCompare(b.schema.name, i18n.language)), [catalog, i18n.language, query])
  const selected = catalog.find((item) => item.schema.uuid === selectedUuid) ?? null
  const notMapped = t('schemas.notMapped')
  const stateText = { complete: tr ? 'Tüm Ortamlar Eşlendi' : 'All environments mapped', partial: tr ? 'Eksik Eşleme' : 'Partially mapped', none: notMapped }
  const addButton = canManage ? <Button tone="primary" icon={<Plus size={16} />} onClick={() => setCreating(true)}>{t('schemas.addLogical')}</Button> : undefined

  return <section className="page-stack connections-page logical-schemas-page">
    <section className="connection-management-panel"><PageHeader icon={<GitBranch />} eyebrow={t('schemas.eyebrow')} title={t('schemas.logicalTitle')} description={t('schemas.logicalDescription')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Ad, kod veya sağlayıcıya göre ara' : 'Search by name, code or provider'} /></section>
    <SummaryStrip ariaLabel={t('schemas.logicalTitle')} items={[
      { label: tr ? 'Toplam Mantıksal Şema' : 'Total Logical Schemas', value: catalog.length, icon: <GitBranch />, tone: 'info' },
      { label: tr ? 'Tam Eşlenmiş' : 'Fully Mapped', value: catalog.filter((item) => mappingState(item) === 'complete').length, icon: <Link2 />, tone: 'success' },
      { label: t('schemas.environmentsTitle'), value: environments.length, icon: <Workflow />, tone: 'neutral' },
      { label: t('schemas.physicalTitle'), value: physicalSchemas.length, icon: <Database />, tone: 'teal' },
      { label: t('connections.title'), value: connections.length, icon: <Cable />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('schemas.noLogical')} action={addButton} /> : <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid auditKind="logical-schemas" auditInFooter collectionTitle={tr ? 'Mantıksal Şema Kataloğu' : 'Logical Schema Catalog'} collectionIcon={<GitBranch />} toolbarActions={addButton} cardHeaderLeadingField="provider" cardHeaderField="mapping" cardHiddenFields={['provider', 'mapping']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr>
        <th data-field-key="provider">{t('connections.provider')}</th><th data-field-key="name">{t('schemas.logicalSchema')}</th><th data-field-key="description">{t('schemas.description')}</th>{environments.map((environment) => <th key={environment.uuid} data-field-key={`env-${environment.code}`}>{environment.name}</th>)}<th data-field-key="mapping">{tr ? 'Eşleme Durumu' : 'Mapping Status'}</th><th data-field-key="status">{t('connections.status')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th>
      </tr></thead><tbody>{visible.map((item) => { const state = mappingState(item); const tone = state === 'complete' ? 'success' : 'warning'; return <tr key={item.schema.uuid} data-connection-uuid={item.schema.uuid}>
        <td><span className="provider-cell"><DatabaseProviderIcon databaseType={item.schema.databaseType ?? ''} /><span>{databaseProviderVisual(item.schema.databaseType ?? '').label}</span></span></td>
        <td><span className="connection-record-identity"><strong>{item.schema.name}</strong><small>{item.schema.code}</small></span></td>
        <td>{item.schema.description || t('common.noDescription')}</td>
        {item.mappings.map((mapping) => <td key={mapping.environment.uuid}>{mapping.binding ? mappingLabel(mapping, notMapped).split(' → ')[1] : notMapped}</td>)}
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={state === 'complete' ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{stateText[state]}</span></Tag></td>
        <td>{item.schema.status === 'ETKIN' ? (tr ? 'Etkin' : 'Active') : (tr ? 'Pasif' : 'Inactive')}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={item.schema.name} editable={canManage} onClick={() => setSelectedUuid(item.schema.uuid)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>
    {selected && <LogicalSchemaDetailDialog key={`${selected.schema.uuid}:${selected.mappedCount}:${selected.schema.name}`} item={selected} physicalSchemas={physicalSchemas} connections={connections} onClose={() => setSelectedUuid(null)} onChanged={async (deleted) => { if (deleted) setSelectedUuid(null); await load() }} />}
    <Dialog open={canManage && creating} title={t('schemas.addLogical')} closeLabel={t('common.close')} onClose={() => setCreating(false)} className="connection-catalog-dialog">
      <LogicalSchemaForm projectUuid={projectUuid} environments={environments} physicalSchemas={physicalSchemas} connections={connections} onClose={() => setCreating(false)} onCreated={async (created) => { setCreating(false); await load(); setSelectedUuid(created.uuid) }} />
    </Dialog>
  </section>
}
