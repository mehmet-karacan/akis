import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { Cable, Database, GitBranch, Plus, Search, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, FilterBar, PageHeader, SummaryStrip } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi } from '../topology/api'
import type { ConnectionCatalogItem } from './catalog'
import { ConnectionsTable } from './ConnectionsTable'
import { ConnectionCards } from './ConnectionCards'
import { useCollectionView, ViewToggle } from '../../core/ui/ViewToggle'
import { ConnectionDetailPage } from './ConnectionDetailPage'
import { OracleConnectionCreateForm } from '../topology/OracleConnectionCreateForm'
import { getTopologyCopy } from '../topology/copy'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import './connections.css'
import './catalog-layout.css'
import { FilterSection } from '../../core/ui/FilterSection'



export function ConnectionsPage() {
  const projectUuid = useCurrentProjectUuid()
  const [view, setView] = useCollectionView('akis:connections:view')
  const { t, i18n } = useTranslation()
  const [params, setParams] = useSearchParams()
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [draftFilters, setDraftFilters] = useState({ q: params.get('q') ?? '', provider: params.get('provider') ?? 'ALL', sort: params.get('sort') ?? 'name' })
  const [catalog, setCatalog] = useState<ConnectionCatalogItem[]>([])
  const audit = useRecordAudit('connections', catalog.map(item => `${item.connection.uuid}:${item.connection.version}:${item.latestVersionNumber}`).join(','))
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { can } = useProjectAccess()

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const items = await topologyApi.listConnectionCatalog(projectUuid)
      setCatalog(items.map((item) => ({
        connection: item.connection,
        displayedVersion: item.displayedVersion ?? undefined,
        latestVersionNumber: item.latestVersionNumber ?? undefined,
        physicalSchemaCount: item.physicalSchemaCount,
        logicalSchemaCount: item.logicalSchemaCount,
      })))
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])
  const query = params.get('q') ?? ''
  const provider = params.get('provider') ?? 'ALL'
  const sort = params.get('sort') ?? 'name'
  const applyFilters = () => setParams({ q: draftFilters.q, provider: draftFilters.provider, sort: draftFilters.sort })

  const filtered = useMemo(() => catalog
    .filter((item) => provider === 'ALL' || item.connection.databaseType === provider)
    .filter((item) => `${item.connection.name} ${item.connection.code} ${item.connection.databaseType}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => sort === 'code' ? a.connection.code.localeCompare(b.connection.code, i18n.language) : a.connection.name.localeCompare(b.connection.name, i18n.language)), [catalog, i18n.language, provider, query, sort])
  const labels = { host: i18n.language === 'tr' ? 'Sunucu' : 'Host', port: 'Port', service: i18n.language === 'tr' ? 'Servis Adı veya SID' : 'Service Name or SID', username: i18n.language === 'tr' ? 'Kullanıcı Adı' : 'Username', provider: t('connections.provider'), connection: t('connections.connection'), endpoint: t('connections.endpoint'), physical: t('connections.physicalShort'), logical: t('connections.logicalShort'), status: t('connections.status'), actions: t('connections.actions'), open: t('connections.open'), ready: t('connections.ready'), testRequired: t('connections.testRequired') }

  return <section className="page-stack connections-page">
    <section className="connection-management-panel"><PageHeader title={t('connections.title')} description={t('connections.description')} />
    <FilterSection><FilterBar>
      <label className="connections-search"><span>{t('connections.search')}</span><AntInput value={draftFilters.q} onChange={(event) => setDraftFilters(current => ({ ...current, q: event.target.value }))} placeholder={t('connections.searchPlaceholder')} /></label>
      <label><span>{t('connections.provider')}</span><FormSelect value={draftFilters.provider} onChange={(event) => setDraftFilters(current => ({ ...current, provider: event.target.value }))}><option value="ALL">{t('connections.allProviders')}</option><option value="ORACLE">Oracle</option></FormSelect></label>
      <label><span>{t('connections.sort')}</span><FormSelect value={draftFilters.sort} onChange={(event) => setDraftFilters(current => ({ ...current, sort: event.target.value }))}><option value="name">{t('connections.sortName')}</option><option value="code">{t('connections.sortCode')}</option></FormSelect></label>
    </FilterBar><div className="connection-filter-actions"><Button onClick={() => { setDraftFilters({ q: '', provider: 'ALL', sort: 'name' }); setParams({}) }}>{i18n.language === 'tr' ? 'Temizle' : 'Clear'}</Button><Button tone="primary" icon={<Search size={16} />} onClick={applyFilters}>{i18n.language === 'tr' ? 'Sorgula' : 'Search'}</Button></div></FilterSection></section>
    <SummaryStrip ariaLabel={i18n.language === 'tr' ? 'Bağlantı özeti' : 'Connection summary'} items={[
      { label: i18n.language === 'tr' ? 'Toplam Bağlantı' : 'Total Connections', value: catalog.length, icon: <Cable />, tone: 'info' },
      { label: i18n.language === 'tr' ? 'Fiziksel Şema' : 'Physical Schemas', value: catalog.reduce((sum, item) => sum + item.physicalSchemaCount, 0), icon: <Database />, tone: 'info' },
      { label: i18n.language === 'tr' ? 'Mantıksal Şema' : 'Logical Schemas', value: catalog.reduce((sum, item) => sum + item.logicalSchemaCount, 0), icon: <GitBranch />, tone: 'neutral' },
      { label: i18n.language === 'tr' ? 'Test Edilmiş' : 'Tested', value: catalog.filter((item) => item.displayedVersion?.testedAt).length, icon: <ShieldCheck />, tone: 'success' },
    ]} />
    <section className="connections-records"><header className="connections-records-header"><h2>{i18n.language === 'tr' ? 'Bağlantı Listesi' : 'Connection List'}</h2><div className="connection-view-toolbar">{can('BAGLANTI_YONET') && <Button tone="primary" icon={<Plus size={16} />} onClick={() => setCreating(true)}>{t('connections.add')}</Button>}<div className="ui-grid-view-control"><span>{i18n.language === 'tr' ? 'Görünüm' : 'View'}</span><ViewToggle value={view} onChange={setView} /></div></div></header>
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('connections.empty')} description={t('connections.emptyHint')} /> : <ProgressiveRecords key={`${query}:${provider}:${sort}`} items={filtered}>{(visible) => view === 'table' ?  <ConnectionsTable projectUuid={projectUuid} items={visible} labels={labels} onOpen={setSelectedUuid} /> : <ConnectionCards audit={audit} items={visible} view={view} onOpen={setSelectedUuid} />}</ProgressiveRecords>}
    </section>
    <Dialog open={selectedUuid !== null} title={t('connections.details')} closeLabel={t('common.close')} onClose={() => setSelectedUuid(null)} className="connection-catalog-dialog">
      {selectedUuid && <ConnectionDetailPage key={selectedUuid} selectedUuid={selectedUuid} onChanged={() => void load()} onDeleted={() => { setSelectedUuid(null); void load() }} />}
    </Dialog>
    <Dialog open={creating} title={t('connections.createTitle')} closeLabel={t('common.close')} onClose={() => setCreating(false)} className="connection-catalog-dialog">
      <OracleConnectionCreateForm projectUuid={projectUuid} copy={getTopologyCopy(i18n.language)} onClose={() => setCreating(false)} onConnectionCreated={async (uuid) => { setCreating(false); await load(); setSelectedUuid(uuid) }} />
    </Dialog>
  </section>
}
