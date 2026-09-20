import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { Tag } from 'antd'
import { Cable, CheckCircle2, CircleAlert, Database, GitBranch, Plus, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi } from '../topology/api'
import type { ConnectionCatalogItem } from './catalog'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { ConnectionDetailPage } from './ConnectionDetailPage'
import { ConnectionForm } from '../topology/ConnectionForm'
import { getTopologyCopy } from '../topology/copy'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import './connections.css'
import './catalog-layout.css'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { ConnectionTestButton } from './ConnectionTestButton'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { connectionStatusTagStyles, createConnectionPresentation } from './presentation'

const trLabel = (language: string, tr: string, en: string) => language === 'tr' ? tr : en



export function ConnectionsPage() {
  const projectUuid = useCurrentProjectUuid()
  const [view, setView] = useCollectionView('akis:connections:view')
  const { t, i18n } = useTranslation()
  const [params, setParams] = useSearchParams()
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [catalog, setCatalog] = useState<ConnectionCatalogItem[]>([])
  const audit = useRecordAudit('connections', catalog.map(item => `${item.connection.uuid}:${item.connection.updatedAt ?? ''}`).join(','))
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { can } = useProjectAccess()

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const items = await topologyApi.listConnectionCatalog(projectUuid)
      setCatalog(items)
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])
  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})

  const filtered = useMemo(() => catalog
    .filter((item) => `${item.connection.name} ${item.connection.code} ${item.connection.databaseType}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => a.connection.name.localeCompare(b.connection.name, i18n.language)), [catalog, i18n.language, query])
  const labels = { host: i18n.language === 'tr' ? 'Sunucu' : 'Host', port: 'Port', service: i18n.language === 'tr' ? 'Servis Adı veya SID' : 'Service Name or SID', username: i18n.language === 'tr' ? 'Kullanıcı Adı' : 'Username', provider: t('connections.provider'), connection: t('connections.connection'), endpoint: t('connections.endpoint'), physical: i18n.language === 'tr' ? 'Fiziksel Şema' : 'Physical Schemas', logical: i18n.language === 'tr' ? 'Mantıksal Şema' : 'Logical Schemas', status: t('connections.status'), lastTest: i18n.language === 'tr' ? 'Son Test Zamanı' : 'Last Tested At', actions: t('connections.actions'), open: t('connections.open'), ready: t('connections.ready'), testRequired: t('connections.testRequired') }

  return <section className="page-stack connections-page">
    <section className="connection-management-panel"><PageHeader icon={<Cable />} eyebrow={i18n.language === 'tr' ? 'BAĞLANTI TANIMLARI' : 'CONNECTION DEFINITIONS'} title={t('connections.title')} description={t('connections.description')} />
    <QueryFilter onApply={applyQuery} placeholder={t('connections.searchPlaceholder')} /></section>
    <SummaryStrip ariaLabel={i18n.language === 'tr' ? 'Bağlantı özeti' : 'Connection summary'} items={[
      { label: i18n.language === 'tr' ? 'Toplam Bağlantı' : 'Total Connections', value: catalog.length, icon: <Cable />, tone: 'info' },
      { label: i18n.language === 'tr' ? 'Fiziksel Şema' : 'Physical Schemas', value: catalog.reduce((sum, item) => sum + item.physicalSchemaCount, 0), icon: <Database />, tone: 'teal' },
      { label: i18n.language === 'tr' ? 'Mantıksal Şema' : 'Logical Schemas', value: catalog.reduce((sum, item) => sum + item.logicalSchemaCount, 0), icon: <GitBranch />, tone: 'neutral' },
      { label: i18n.language === 'tr' ? 'Test Edilmiş' : 'Tested', value: catalog.filter((item) => item.connection.lastTestPassed).length, icon: <ShieldCheck />, tone: 'success' },
    ]} />
    <section className="connections-records">
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('connections.empty')} description={t('connections.emptyHint')} action={can('BAGLANTI_YONET') ? <Button tone="primary" icon={<Plus size={16} />} onClick={() => setCreating(true)}>{t('connections.createTitle')}</Button> : undefined} /> : <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid auditKind="connections" auditInFooter collectionTitle={i18n.language === 'tr' ? 'Bağlantı Kataloğu' : 'Connection Catalog'} collectionIcon={<Cable />} toolbarActions={can('BAGLANTI_YONET') ? <Button tone="primary" icon={<Plus size={16} />} onClick={() => setCreating(true)}>{t('connections.createTitle')}</Button> : undefined} cardHeaderLeadingField="provider" cardHeaderField="status" cardHiddenFields={['provider', 'status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr>
        <th data-field-key="provider">{labels.provider}</th><th data-field-key="connection">{labels.connection}</th><th data-field-key="description">{trLabel(i18n.language, 'Açıklama', 'Description')}</th><th data-field-key="host">{labels.host}</th><th data-field-key="port">{labels.port}</th><th data-field-key="service">{labels.service}</th><th data-field-key="username">{labels.username}</th><th data-field-key="connectionType">{trLabel(i18n.language, 'Bağlantı Türü', 'Connection Type')}</th><th data-field-key="status">{labels.status}</th><th data-field-key="lastTest">{labels.lastTest}</th><th data-field-key="physical">{labels.physical}</th><th data-field-key="logical">{labels.logical}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{trLabel(i18n.language, 'İşlemler', 'Actions')}</span></th>
      </tr></thead><tbody>{visible.map((item) => { const { connection, physicalSchemaCount, logicalSchemaCount } = item; const presentation = createConnectionPresentation(item, audit.records[connection.uuid], i18n.language, audit.state); return <tr key={connection.uuid} data-connection-uuid={connection.uuid}>
        <td><span className="provider-cell"><DatabaseProviderIcon databaseType={connection.databaseType} /><span>{databaseProviderVisual(connection.databaseType).label}</span></span></td>
        <td><span className="connection-record-identity"><strong>{presentation.identity.name}</strong><small>{presentation.identity.code}</small></span></td>
        <td>{presentation.identity.description}</td><td>{presentation.values.host}</td><td>{presentation.values.port}</td><td>{presentation.values.service}</td><td>{presentation.values.username}</td><td>{presentation.values.mode}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${presentation.status.tested ? 'success' : 'warning'}`} style={connectionStatusTagStyles[presentation.status.tested ? 'success' : 'warning']} icon={presentation.status.tested ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{presentation.status.text}</span></Tag></td><td>{presentation.lastTest}</td><td>{physicalSchemaCount}</td><td>{logicalSchemaCount}</td>
        <td className="row-actions"><div className="connection-row-actions"><ConnectionTestButton connectionUuid={connection.uuid} onTested={() => void load()} /><RecordActionButton name={connection.name} editable={false} onClick={() => setSelectedUuid(connection.uuid)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>
    <RecordDetailDialog open={selectedUuid !== null} title={selectedUuid ? <span className="connection-dialog-title"><Cable size={17} aria-hidden="true" />{catalog.find(item => item.connection.uuid === selectedUuid)?.connection.name ?? t('connections.details')}</span> : t('connections.details')} readOnly={!can('BAGLANTI_YONET')} onClose={() => setSelectedUuid(null)} className="connection-catalog-dialog">
      {selectedUuid && <ConnectionDetailPage key={selectedUuid} selectedUuid={selectedUuid} onChanged={() => void load()} onDeleted={() => { setSelectedUuid(null); void load() }} />}
    </RecordDetailDialog>
    <Dialog open={creating} title={t('connections.createTitle')} closeLabel={t('common.close')} onClose={() => setCreating(false)} className="connection-catalog-dialog">
      <ConnectionForm projectUuid={projectUuid} copy={getTopologyCopy(i18n.language)} onClose={() => setCreating(false)} onSaved={async (created) => { setCreating(false); await load(); setSelectedUuid(created.uuid) }} />
    </Dialog>
  </section>
}
