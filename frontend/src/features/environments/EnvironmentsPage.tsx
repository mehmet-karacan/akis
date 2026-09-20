import { Tag } from 'antd'
import { CheckCircle2, CircleAlert, Database, GitBranch, Link2, Plus, ShieldAlert, Star, Workflow } from 'lucide-react'
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
import { topologyApi, type Connection, type LogicalSchema, type PhysicalSchema } from '../topology/api'
import { connectionStatusTagStyles } from '../connections/presentation'
import { buildEnvironmentCatalog, riskLabel, riskTone, type EnvironmentCatalogItem } from './environmentCatalog'
import { EnvironmentForm } from './EnvironmentForm'
import { EnvironmentDetailDialog } from './EnvironmentDetailDialog'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import '../schemas/schemas.css'

/** Same catalog layout as the connections screen: header + filter, summary strip, card/list/table records. */
export function EnvironmentsPage() {
  const projectUuid = useCurrentProjectUuid()
  const [view, setView] = useCollectionView('akis:environments:view')
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [params, setParams] = useSearchParams()
  const { can } = useProjectAccess()
  const canManage = can('BAGLANTI_YONET')
  const [catalog, setCatalog] = useState<EnvironmentCatalogItem[]>([])
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [physicalSchemas, setPhysicalSchemas] = useState<PhysicalSchema[]>([])
  const [connections, setConnections] = useState<Connection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [environments, nextLogical, nextPhysical, nextConnections, bindings] = await Promise.all([
        topologyApi.listEnvironments(projectUuid),
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listConnections(projectUuid),
        topologyApi.listBindings(projectUuid),
      ])
      setLogicalSchemas(nextLogical); setPhysicalSchemas(nextPhysical); setConnections(nextConnections)
      setCatalog(buildEnvironmentCatalog(environments, nextLogical, nextPhysical, nextConnections, bindings))
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])
  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})

  const filtered = useMemo(() => catalog
    .filter((item) => `${item.environment.name} ${item.environment.code} ${riskLabel(item.environment.risk, tr)} ${item.environment.description ?? ''}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => a.environment.name.localeCompare(b.environment.name, i18n.language)), [catalog, i18n.language, query, tr])
  const selected = catalog.find((item) => item.environment.uuid === selectedUuid) ?? null
  const notMapped = t('schemas.notMapped')
  const addButton = canManage ? <Button tone="primary" icon={<Plus size={16} />} onClick={() => setCreating(true)}>{t('schemas.addEnvironment')}</Button> : undefined

  return <section className="page-stack connections-page environments-page">
    <section className="connection-management-panel"><PageHeader icon={<Workflow />} eyebrow={t('schemas.eyebrow')} title={t('schemas.environmentsTitle')} description={t('schemas.environmentsDescription')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Ad, kod veya risk sınıfına göre ara' : 'Search by name, code or risk class'} /></section>
    <SummaryStrip ariaLabel={t('schemas.environmentsTitle')} items={[
      { label: tr ? 'Toplam Ortam' : 'Total Environments', value: catalog.length, icon: <Workflow />, tone: 'info' },
      { label: tr ? 'Üretim Ortamı' : 'Production Environments', value: catalog.filter((item) => item.environment.risk === 'URETIM').length, icon: <ShieldAlert />, tone: 'warning' },
      { label: tr ? 'Tam Eşlenmiş' : 'Fully Mapped', value: catalog.filter((item) => item.mappings.length > 0 && item.mappedCount === item.mappings.length).length, icon: <Link2 />, tone: 'success' },
      { label: t('schemas.logicalTitle'), value: logicalSchemas.length, icon: <GitBranch />, tone: 'neutral' },
      { label: t('schemas.physicalTitle'), value: physicalSchemas.length, icon: <Database />, tone: 'teal' },
    ]} />
    <section className="connections-records">
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('schemas.noEnvironments')} action={addButton} /> : <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid auditKind="environments" auditInFooter collectionTitle={tr ? 'Ortam Kataloğu' : 'Environment Catalog'} collectionIcon={<Workflow />} toolbarActions={addButton} cardHeaderLeadingField="risk" cardHeaderField="mapping" cardHiddenFields={['risk', 'mapping']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr>
        <th data-field-key="risk">{tr ? 'Risk' : 'Risk'}</th><th data-field-key="name">{t('schemas.environment')}</th><th data-field-key="description">{t('schemas.description')}</th>{logicalSchemas.map((schema) => <th key={schema.uuid} data-field-key={`ls-${schema.code}`}>{schema.name}</th>)}<th data-field-key="mapping">{tr ? 'Eşleme Durumu' : 'Mapping Status'}</th><th data-field-key="default">{tr ? 'Varsayılan' : 'Default'}</th><th data-field-key="status">{t('connections.status')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th>
      </tr></thead><tbody>{visible.map((item) => { const complete = item.mappings.length > 0 && item.mappedCount === item.mappings.length; const tone = complete ? 'success' : 'warning'; const risk = riskTone(item.environment.risk); return <tr key={item.environment.uuid} data-connection-uuid={item.environment.uuid}>
        <td><Tag className={`connection-status-tag connection-status-tag--${risk}`} style={connectionStatusTagStyles[risk]} icon={<ShieldAlert size={12} />}><span className="connection-status-tag-label">{riskLabel(item.environment.risk, tr)}</span></Tag></td>
        <td><span className="connection-record-identity"><strong>{item.environment.name}</strong><small>{item.environment.code}</small></span></td>
        <td>{item.environment.description || t('common.noDescription')}</td>
        {item.mappings.map((mapping) => <td key={mapping.logicalSchema.uuid}>{mapping.binding ? `${mapping.connection?.code ?? '?'} / ${mapping.physicalSchema?.schemaName ?? '?'}` : notMapped}</td>)}
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={complete ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{complete ? (tr ? 'Tüm Şemalar Eşlendi' : 'All schemas mapped') : item.mappedCount > 0 ? (tr ? 'Eksik Eşleme' : 'Partially mapped') : notMapped}</span></Tag></td>
        <td>{item.environment.defaultEnvironment ? <span className="physical-schema-default"><Star size={14} />{tr ? 'Varsayılan' : 'Default'}</span> : null}</td>
        <td>{item.environment.status === 'ETKIN' ? (tr ? 'Etkin' : 'Active') : (tr ? 'Pasif' : 'Inactive')}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={item.environment.name} editable={canManage} onClick={() => setSelectedUuid(item.environment.uuid)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>
    {selected && <EnvironmentDetailDialog key={`${selected.environment.uuid}:${selected.mappedCount}:${selected.environment.name}:${selected.environment.risk}:${selected.environment.defaultEnvironment}`} item={selected} physicalSchemas={physicalSchemas} connections={connections} onClose={() => setSelectedUuid(null)} onChanged={async (deleted) => { if (deleted) setSelectedUuid(null); await load() }} />}
    <Dialog open={canManage && creating} title={t('schemas.addEnvironment')} closeLabel={t('common.close')} onClose={() => setCreating(false)} className="connection-catalog-dialog">
      <EnvironmentForm projectUuid={projectUuid} onClose={() => setCreating(false)} onCreated={async (created) => { setCreating(false); await load(); setSelectedUuid(created.uuid) }} />
    </Dialog>
  </section>
}
