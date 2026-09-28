import { DataGrid } from '../../core/ui/DataGrid'
import { Checkbox as AntCheckbox } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { ArrowLeft, Database, FilePlus2, ListChecks, RotateCcw, Save, ScanSearch, Table2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, PageHeader, RecordActionButton, SummaryStrip } from '../../core/ui'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { MetadataDiscoveryDetail } from './MetadataDiscoveryDetail'
import { discoveryTableKey, importModelMetadata } from './importModelMetadata'
import { topologyApi, type Connection, type DataObject, type DiscoveryResult, type DiscoveryTable, type Environment, type Model, type Submodel, type PhysicalSchema, type SchemaBinding, type SchemaSnapshot } from '../topology/api'
import './models.css'
import '../execution/execution.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import { matchesCatalogReference, modelPath, resolveModelReference } from './modelRoutes'
import { DataObjectTypeIcon, dataObjectTypeLabel } from './DataObjectTypeIcon'

function typeIcon(type: string) {
  return <DataObjectTypeIcon type={type} />
}

export function MetadataImportPage({ embedded = false, onImported, initialModel }: { embedded?: boolean; onImported?: () => void; initialModel?: Model }) {
  const { modelUuid: modelReference = '' } = useParams()
  const projectUuid = useCurrentProjectUuid()
  const [params] = useSearchParams()
  return <MetadataImportSession key={`${projectUuid}:${modelReference}:${params.get('refresh') ?? (embedded ? '' : params.get('object')) ?? ''}:${params.get('folder') ?? ''}`} projectUuid={projectUuid} modelReference={modelReference} embedded={embedded} onImported={onImported} initialModel={initialModel} />
}

function MetadataImportSession({ projectUuid, modelReference, embedded, onImported, initialModel }: { projectUuid: string; modelReference: string; embedded: boolean; onImported?: () => void; initialModel?: Model }) {
  const { t, i18n } = useTranslation(); const [params] = useSearchParams(); const tr = i18n.language.startsWith('tr')
  const mounted = useRef(true)
  const [view, setView] = useCollectionView('akis:metadata-import:view')
  const generation = useRef(0)
  const importPending = useRef(false)
  const [importProgress, setImportProgress] = useState<{ completed: number; total: number } | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current += 1 } }, [])
  const requestedObjectReference = params.get('refresh') ?? (embedded ? null : params.get('object')); const requestedFolderReference = params.get('folder')
  const [folders, setFolders] = useState<Submodel[]>([]); const [folderUuid, setFolderUuid] = useState('')
  const [model, setModel] = useState<Model | null>(null); const [environments, setEnvironments] = useState<Environment[]>([]); const [bindings, setBindings] = useState<SchemaBinding[]>([]); const [physical, setPhysical] = useState<PhysicalSchema[]>([]); const [connections, setConnections] = useState<Connection[]>([]); const [objects, setObjects] = useState<DataObject[]>([])
  const [environmentUuid, setEnvironmentUuid] = useState(''); const [scope, setScope] = useState(''); const [result, setResult] = useState<DiscoveryResult | null>(null); const [selected, setSelected] = useState<Set<string>>(new Set()); const [preview, setPreview] = useState<DiscoveryTable | null>(null); const [previous, setPrevious] = useState<SchemaSnapshot | null>(null)
  const [snapshotStatus, setSnapshotStatus] = useState<'loading' | 'ready' | 'unavailable'>('ready')
  const [discoveryType, setDiscoveryType] = useState('ALL')
  const [resultTypeFilter, setResultTypeFilter] = useState('ALL')
  const [loading, setLoading] = useState(true); const [discovering, setDiscovering] = useState(false); const [importing, setImporting] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const load = useCallback(async () => { const request = ++generation.current; setLoading(true); setError(''); try { const nextModel = initialModel ?? await resolveModelReference(projectUuid, modelReference); const [nextEnvironments, nextBindings, nextPhysical, nextConnections, nextObjects, nextFolders] = await Promise.all([topologyApi.listEnvironments(projectUuid), topologyApi.listBindings(projectUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listConnections(projectUuid), topologyApi.listDataObjects(projectUuid, nextModel.uuid), topologyApi.listSubmodels(projectUuid, nextModel.uuid)]); if (!mounted.current || request !== generation.current) return; setModel(nextModel); setEnvironments(nextEnvironments); setBindings(nextBindings); setPhysical(nextPhysical); setConnections(nextConnections); setObjects(nextObjects); setFolders(nextFolders); setEnvironmentUuid((value) => nextEnvironments.length === 1 ? nextEnvironments[0]!.uuid : nextEnvironments.some(item => item.uuid === value) ? value : nextEnvironments.find(item => item.uuid === nextModel.reverseEnvironmentUuid)?.uuid ?? nextEnvironments[0]?.uuid ?? ''); const object = nextObjects.find(item => matchesCatalogReference(item, requestedObjectReference)); setFolderUuid(object?.submodelUuid ?? nextFolders.find(item => matchesCatalogReference(item, requestedFolderReference))?.uuid ?? ''); if (object) setScope(object.objectReference.toUpperCase()) } catch { if (mounted.current && request === generation.current) setError(t('common.loadError')) } finally { if (mounted.current && request === generation.current) setLoading(false) } }, [initialModel, modelReference, projectUuid, requestedFolderReference, requestedObjectReference, t])
  useEffect(() => { void load() }, [load])
  const binding = useMemo(() => bindings.find((item) => item.logicalSchemaUuid === model?.logicalSchemaUuid && item.environmentUuid === environmentUuid), [bindings, environmentUuid, model?.logicalSchemaUuid])
  const physicalSchema = physical.find((item) => item.uuid === binding?.physicalSchemaUuid); const connection = connections.find((item) => item.uuid === physicalSchema?.connectionUuid)
  useEffect(() => { if (!preview) { setPrevious(null); setSnapshotStatus('ready'); return } const existing = objects.find((item) => item.objectReference.toLocaleUpperCase() === preview.name.toLocaleUpperCase()); if (!existing) { setPrevious(null); setSnapshotStatus('ready'); return } let active = true; setPrevious(null); setSnapshotStatus('loading'); void topologyApi.listSchemaSnapshots(projectUuid, existing.uuid).then((rows) => { if (active) { setPrevious(rows[0] ?? null); setSnapshotStatus(rows.length ? 'ready' : 'unavailable') } }).catch(() => { if (active) { setPrevious(null); setSnapshotStatus('unavailable') } }); return () => { active = false } }, [objects, preview, projectUuid])
  async function discover() { if (model?.reverseMode === 'CUSTOM_RKM' || !binding || !physicalSchema || !connection || discovering || importing) return; const request = ++generation.current; setDiscovering(true); setImportProgress(null); setResult(null); setPreview(null); setPrevious(null); setSelected(new Set()); setResultTypeFilter('ALL'); setError(''); setNotice(''); try { const next = await topologyApi.discoverOracle(projectUuid, connection.uuid, physicalSchema.uuid, { tableName: scope.trim() || undefined, types: discoveryType === 'ALL' ? undefined : [discoveryType], limit: 100 }); if (!mounted.current || request !== generation.current) return; setResult(next); setSelected(new Set()); setPreview(next.tables[0] ?? null) } catch (reason) { if (mounted.current && request === generation.current) setError(reason instanceof Error ? reason.message : t('models.discoveryFailed')) } finally { if (mounted.current && request === generation.current) setDiscovering(false) } }
  const toggle = (table: DiscoveryTable) => setSelected((current) => { const key = `${table.owner}.${table.name}`; const next = new Set(current); if (next.has(key)) next.delete(key); else next.add(key); return next })
  async function importSelected() {
    if (!model || !binding || !physicalSchema || !connection || !result || discovering || importPending.current) return
    const tables = result.tables.filter(table => selected.has(discoveryTableKey(table)))
    if (!tables.length) return
    if (result.connectionUuid !== connection.uuid || result.physicalSchemaUuid !== physicalSchema.uuid) {
      setError(tr ? 'Bağlantı bağlamı değişti. Metadata keşfini yeniden çalıştırın.' : 'The connection context changed. Discover the metadata again.')
      return
    }
    importPending.current = true
    setImporting(true); setError(''); setNotice(''); setImportProgress({ completed: 0, total: tables.length })
    try {
      const count = await importModelMetadata(tables, {
        listObjects: () => topologyApi.listDataObjects(projectUuid, model.uuid),
        createObject: table => topologyApi.createDataObject(projectUuid, model.uuid, {
          submodelUuid: folderUuid || null,
          code: `${table.owner}_${table.name}`.replace(/[^A-Za-z0-9_]/g, '_').slice(0, 100).toUpperCase(),
          name: table.name, objectReference: table.name, type: table.type,
        }),
        captureSnapshot: object => topologyApi.captureOracleSchemaSnapshot(projectUuid, connection.uuid, physicalSchema.uuid, object.uuid),
        isActive: () => mounted.current,
        onRegistered: object => {
          window.dispatchEvent(new Event('akis:models-changed'))
          if (mounted.current) setObjects(current => [...current.filter(item => item.uuid !== object.uuid), object])
        },
        onImported: (table, completed) => {
          window.dispatchEvent(new Event('akis:models-changed'))
          if (!mounted.current) return
          setSelected(current => { const next = new Set(current); next.delete(discoveryTableKey(table)); return next })
          setImportProgress({ completed, total: tables.length })
        },
      })
      if (mounted.current) { setNotice(t('models.importedCount', { count })); onImported?.() }
    } catch (reason) {
      if (mounted.current) setError(reason instanceof Error && reason.message === 'METADATA_OBJECT_AMBIGUOUS'
        ? (tr ? 'Aynı tablo için birden fazla Data Store bulundu. Katalog kaydını kontrol edin.' : 'More than one data store matches the table. Check the catalog before retrying.')
        : reason instanceof Error ? reason.message : t('models.importFailed'))
    } finally {
      importPending.current = false
      if (mounted.current) setImporting(false)
    }
  }
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (!model) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const existingFor = (table: DiscoveryTable) => objects.find((item) => item.objectReference.toLocaleUpperCase() === table.name.toLocaleUpperCase())
  const technology = model.technologyCode ?? 'ORACLE'
  const contextReady = Boolean(binding && physicalSchema && connection)
  const resultTypes = [...new Set(result?.tables.map(table => table.type) ?? [])].sort()
  const filteredTables = result?.tables.filter(table => resultTypeFilter === 'ALL' || table.type === resultTypeFilter) ?? []
  const activePreview = preview && filteredTables.includes(preview) ? preview : filteredTables[0] ?? null
  const discoverLabel = requestedObjectReference ? (tr ? 'Metadata’yı Yenile' : 'Refresh Metadata') : t('models.discover')
  const importActions = result ? <div className="metadata-import-toolbar"><span className="metadata-destination-label">{tr ? 'Hedef' : 'Destination'}: {folders.find(item => item.uuid === folderUuid)?.name ?? t('models.modelRoot')}</span><Button disabled={importing || filteredTables.length === 0} onClick={() => setSelected(current => new Set([...current, ...filteredTables.map(discoveryTableKey)]))}>{tr ? 'Filtrelenenleri Seç' : 'Select Filtered'}</Button><Button icon={<RotateCcw size={15} />} disabled={importing || selected.size === 0} onClick={() => setSelected(new Set())}>{tr ? 'Seçimi Temizle' : 'Clear Selection'}</Button><Button tone="primary" icon={<Save size={16} />} disabled={selected.size === 0} busy={importing} onClick={() => void importSelected()}>{t('models.importSelected')} ({selected.size})</Button></div> : undefined
  return <section className={`page-stack connections-page models-page metadata-import-page${embedded ? ' metadata-import-embedded' : ''}`}>{!embedded && <Link className="connection-back-link" to={modelPath(model)}><ArrowLeft size={16} />{model.name}</Link>}
    <FeedbackToast message={error} tone="error" onClose={() => setError('')} /><FeedbackToast message={notice} onClose={() => setNotice('')} />
    <section className="connection-management-panel">{embedded ? <div className="metadata-workspace-intro"><ScanSearch size={20} aria-hidden="true" /><div><h2>Reverse Engineer</h2><p>{environments.length === 1 ? (tr ? 'Nesne türünü seçin; kaynaktaki yapıları inceleyip modele kaydedin.' : 'Choose an object type, inspect source structures, then save them to the model.') : (tr ? 'Türü ve ortamı seçin; kaynaktaki nesneleri inceleyip modele kaydedin.' : 'Choose a type and environment, inspect source objects, then save them to the model.')}</p></div></div> : <PageHeader icon={<ScanSearch />} eyebrow={`${databaseProviderVisual(technology).label} · ${model.code}`} title={requestedObjectReference ? (tr ? 'Data Store Metadata Yenileme' : 'Refresh Data Store Metadata') : t('models.importMetadata')} description={t('models.importDescription')} />}
    <form className="run-filters metadata-discovery-form" onSubmit={(event) => { event.preventDefault(); void discover() }}>
      {environments.length !== 1 && <label><span>{t('models.environment')}</span><FormSelect aria-label={t('models.environment')} value={environmentUuid} disabled={discovering || importing} onChange={(event) => { setEnvironmentUuid(event.target.value); setImportProgress(null); setResult(null); setSelected(new Set()); setPreview(null); setPrevious(null) }}>{environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</FormSelect></label>}
      {contextReady && <label><span>{tr ? 'Keşfedilecek Nesne Türü' : 'Object Type to Discover'}</span><FormSelect aria-label={tr ? 'Keşfedilecek Nesne Türü' : 'Object Type to Discover'} value={discoveryType} disabled={discovering || importing} onChange={event => { setDiscoveryType(event.target.value); setImportProgress(null); setResult(null); setSelected(new Set()); setPreview(null); setPrevious(null) }}><option value="ALL">{tr ? 'Tüm türler' : 'All types'}</option><option value="TABLE">{dataObjectTypeLabel('TABLE', tr)}</option><option value="VIEW">{dataObjectTypeLabel('VIEW', tr)}</option><option value="MATERIALIZED_VIEW">{dataObjectTypeLabel('MATERIALIZED_VIEW', tr)}</option>{technology === 'ORACLE' && <option value="SYNONYM">{dataObjectTypeLabel('SYNONYM', tr)}</option>}</FormSelect></label>}
      {contextReady && <label><span>{t('models.discoveryScope')}</span><AntInput value={scope} disabled={discovering || importing} onChange={(event) => { setScope(technology === 'POSTGRESQL' ? event.target.value : event.target.value.toUpperCase()); setImportProgress(null); setResult(null); setSelected(new Set()); setPreview(null); setPrevious(null) }} placeholder={t('models.scopePlaceholder')} /></label>}
      {contextReady && <div className="run-filter-actions"><Button type="submit" tone="primary" icon={<ScanSearch size={16} />} busy={discovering} disabled={importing || model.reverseMode === 'CUSTOM_RKM'}>{discoverLabel}</Button></div>}
    </form>
    {contextReady && physicalSchema && connection ? <dl className="metadata-context-summary">{environments.length === 1 && <div><dt>{t('models.environment')}</dt><dd>{environments[0]?.name}</dd></div>}<div><dt>{tr ? 'Hedef Klasör' : 'Destination Folder'}</dt><dd>{folders.find(item => item.uuid === folderUuid)?.name ?? t('models.modelRoot')}</dd></div><div><dt>{tr ? 'Teknoloji' : 'Technology'}</dt><dd>{databaseProviderVisual(technology).label}</dd></div><div><dt>{t('models.connection')}</dt><dd>{connection.name}</dd></div><div><dt>{t('models.physicalSchema')}</dt><dd><code>{physicalSchema.schemaName}</code></dd></div></dl> : <div className="metadata-binding-missing"><strong>{t('models.bindingMissing')}</strong><Link to="/project/logical-schemas">{t('models.openBindings')}</Link></div>}
    {contextReady && <p className="form-note">{model.reverseMode === 'CUSTOM_RKM' ? (tr ? 'Özel RKM yürütmesi henüz bağlı değil. Yanlışlıkla standart JDBC keşfi çalıştırmamak için bu modelde keşif kapalıdır.' : 'Custom RKM execution is not connected yet. Discovery is disabled for this model to prevent silently running standard JDBC instead.') : t('models.metadataOnly')}</p>}
    {importProgress && <p role="status" className="form-note">{tr ? `Metadata kaydı tamamlanan: ${importProgress.completed} / ${importProgress.total}. Yalnızca tamamlanmayan seçimler yeniden denenir.` : `Metadata saved: ${importProgress.completed} / ${importProgress.total}. Only unfinished selections are retried.`}</p>}
    </section>
    <SummaryStrip ariaLabel={t('models.importMetadata')} items={[
      { label: tr ? 'Bulunan Nesne' : 'Objects Found', value: result?.tables.length ?? 0, icon: <Database />, tone: 'info' },
      { label: tr ? 'Seçili' : 'Selected', value: selected.size, icon: <ListChecks />, tone: 'success' },
      { label: t('models.newObject'), value: result ? result.tables.filter((table) => !existingFor(table)).length : 0, icon: <FilePlus2 />, tone: 'teal' },
      { label: t('models.existingObject'), value: result ? result.tables.filter((table) => Boolean(existingFor(table))).length : 0, icon: <Table2 />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
      {result && <div className="metadata-results-filter"><label>{tr ? 'Sonuçları Daralt' : 'Narrow Results'}<FormSelect aria-label={tr ? 'Sonuçları Daralt' : 'Narrow Results'} value={resultTypeFilter} onChange={event => { const type = event.target.value; setResultTypeFilter(type); setPreview(result.tables.find(table => type === 'ALL' || table.type === type) ?? null) }}><option value="ALL">{tr ? 'Tüm türler' : 'All types'}</option>{resultTypes.map(type => <option key={type} value={type}>{dataObjectTypeLabel(type, tr)}</option>)}</FormSelect></label><span role="status">{tr ? `${filteredTables.length} / ${result.tables.length} nesne gösteriliyor` : `${filteredTables.length} / ${result.tables.length} objects shown`}</span>{result.truncated && <span className="metadata-partial-warning">{tr ? 'Sonuç sınırlandı; aramayı daraltarak diğer nesneleri keşfedin.' : 'Results are limited; narrow the scope to discover other objects.'}</span>}</div>}
      {discovering ? <AsyncState state="loading" title={tr ? 'Kaynak nesneleri keşfediliyor' : 'Discovering source objects'} /> : !result && contextReady ? <section className="metadata-start-guide" aria-label={tr ? 'Keşif akışı' : 'Discovery steps'}><div className="metadata-start-heading"><ScanSearch size={24} aria-hidden="true" /><div><h2>{tr ? 'Kaynak nesneleri modele alın' : 'Bring source objects into the model'}</h2><p>{tr ? 'Keşif yalnızca metadata okur; kaynak tablolarda veri veya yapı değişikliği yapmaz.' : 'Discovery reads metadata only; it does not change source table data or structure.'}</p></div></div><ol><li><span>1</span><div><strong>{tr ? 'Bağlamı doğrula' : 'Confirm the context'}</strong><p>{tr ? 'Ortam, mantıksal şema ve çözümlenen fiziksel bağlantıyı kontrol edin.' : 'Check the environment, logical schema and resolved physical connection.'}</p></div></li><li><span>2</span><div><strong>{tr ? 'Nesneleri getir' : 'Discover objects'}</strong><p>{tr ? 'Önce türü seçin, gerekirse nesne adıyla daraltın; sonra kaynak yapılarını getirin.' : 'Choose a type first, optionally narrow by object name, then fetch source structures.'}</p></div></li><li><span>3</span><div><strong>{tr ? 'İncele ve kaydet' : 'Review and save'}</strong><p>{tr ? 'Soldan nesneyi seçip sağda detaylarını inceleyin; istenenleri modele kaydedin.' : 'Select an object on the left, inspect it on the right, then save chosen objects.'}</p></div></li></ol></section> : !result ? <AsyncState state="empty" title={t('models.bindingMissing')} /> : result.tables.length === 0 ? <AsyncState state="empty" title={t('models.discoveryResult', { count: 0 })} /> : filteredTables.length === 0 ? <AsyncState state="empty" title={tr ? 'Bu türde nesne bulunmadı' : 'No objects of this type'} /> : <div className="metadata-results-layout"><div className="metadata-results-list"><ProgressiveRecords key={`${result.discoveredAt}:${scope}:${resultTypeFilter}`} items={filteredTables}>{(visible) => <DataGrid collectionTitle={`${t('models.discoveryResult', { count: result.tables.length })} · ${result.owner} · ${result.truncated ? t('models.partialResult') : t('models.completeScope')}`} collectionIcon={<Database />} toolbarActions={importActions} cardHeaderLeadingField="select" cardHeaderField="state" cardHiddenFields={['select', 'state']} view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="select"><span className="sr-only">{t('models.select')}</span></th><th data-field-key="name">{t('models.object')}</th><th data-field-key="type">{t('models.type')}</th><th data-field-key="columns">{t('models.columnCount')}</th><th data-field-key="state">{tr ? 'Katalog' : 'Catalog'}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
        <tbody>{visible.map((table) => { const key = `${table.owner}.${table.name}`; const existing = existingFor(table); return <tr key={key} data-connection-uuid={key} className={activePreview === table ? 'is-selected' : ''}>
          <td><AntCheckbox disabled={importing} checked={selected.has(key)} aria-label={t('models.selectObject', { name: table.name })} onClick={(event) => event.stopPropagation()} onChange={() => toggle(table)} /></td>
          <td><button type="button" className="connection-record-identity metadata-object-open" aria-label={tr ? `${table.name} detayını göster` : `Show ${table.name} details`} onClick={() => setPreview(table)}><strong>{table.name}</strong><small>{table.owner}</small></button></td>
          <td><span className="model-object-kind">{typeIcon(table.type)}{dataObjectTypeLabel(table.type, tr)}</span></td>
          <td>{table.columns.length}</td>
          <td>{existing ? t('models.existingObject') : t('models.newObject')}</td>
          <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={table.name} editable={false} onClick={() => setPreview(table)} /></div></td>
        </tr> })}</tbody>
      </DataGrid>}</ProgressiveRecords></div><MetadataDiscoveryDetail key={activePreview ? discoveryTableKey(activePreview) : 'empty'} table={activePreview} previous={activePreview === preview ? previous : null} isExisting={Boolean(activePreview && existingFor(activePreview))} snapshotStatus={activePreview === preview ? snapshotStatus : 'unavailable'} /></div>}
    </section>
  </section>
}
