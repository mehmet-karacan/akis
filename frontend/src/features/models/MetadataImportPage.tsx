import { DataGrid } from '../../core/ui/DataGrid'
import { Checkbox as AntCheckbox } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { ArrowLeft, Database, Eye, FilePlus2, ListChecks, Save, ScanSearch, Table2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { MetadataDiff } from './MetadataDiff'
import { compareMetadata } from './metadataComparison'
import { discoveryTableKey, importModelMetadata } from './importModelMetadata'
import { topologyApi, type Connection, type DataObject, type DiscoveryResult, type DiscoveryTable, type Environment, type Model, type Submodel, type PhysicalSchema, type SchemaBinding, type SchemaSnapshot } from '../topology/api'
import './models.css'
import '../execution/execution.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

export function MetadataImportPage() {
  const { modelUuid = '' } = useParams()
  const projectUuid = useCurrentProjectUuid()
  const [params] = useSearchParams()
  return <MetadataImportSession key={`${projectUuid}:${modelUuid}:${params.get('object') ?? ''}:${params.get('folder') ?? ''}`} projectUuid={projectUuid} modelUuid={modelUuid} />
}

function MetadataImportSession({ projectUuid, modelUuid }: { projectUuid: string; modelUuid: string }) {
  const { t, i18n } = useTranslation(); const [params] = useSearchParams(); const tr = i18n.language.startsWith('tr')
  const mounted = useRef(true)
  const [view, setView] = useCollectionView('akis:metadata-import:view')
  const generation = useRef(0)
  const importPending = useRef(false)
  const [importProgress, setImportProgress] = useState<{ completed: number; total: number } | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current += 1 } }, [])
  const requestedObjectUuid = params.get('object')
  const [folders, setFolders] = useState<Submodel[]>([]); const [folderUuid, setFolderUuid] = useState(params.get('folder') ?? '')
  useEffect(() => { let active = true; void topologyApi.listSubmodels(projectUuid, modelUuid).then((rows) => { if (active) setFolders(rows) }).catch(() => { if (active) setError(t('common.loadError')) }); return () => { active = false } }, [projectUuid, modelUuid, t])
  const [model, setModel] = useState<Model | null>(null); const [environments, setEnvironments] = useState<Environment[]>([]); const [bindings, setBindings] = useState<SchemaBinding[]>([]); const [physical, setPhysical] = useState<PhysicalSchema[]>([]); const [connections, setConnections] = useState<Connection[]>([]); const [objects, setObjects] = useState<DataObject[]>([])
  const [environmentUuid, setEnvironmentUuid] = useState(''); const [scope, setScope] = useState(''); const [result, setResult] = useState<DiscoveryResult | null>(null); const [selected, setSelected] = useState<Set<string>>(new Set()); const [preview, setPreview] = useState<DiscoveryTable | null>(null); const [previous, setPrevious] = useState<SchemaSnapshot | null>(null)
  const [loading, setLoading] = useState(true); const [discovering, setDiscovering] = useState(false); const [importing, setImporting] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const load = useCallback(async () => { const request = ++generation.current; setLoading(true); setError(''); try { const [nextModel, nextEnvironments, nextBindings, nextPhysical, nextConnections, nextObjects] = await Promise.all([topologyApi.getModel(projectUuid, modelUuid), topologyApi.listEnvironments(projectUuid), topologyApi.listBindings(projectUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listConnections(projectUuid), topologyApi.listDataObjects(projectUuid, modelUuid)]); if (!mounted.current || request !== generation.current) return; setModel(nextModel); setEnvironments(nextEnvironments); setBindings(nextBindings); setPhysical(nextPhysical); setConnections(nextConnections); setObjects(nextObjects); setEnvironmentUuid((value) => value || nextModel.reverseEnvironmentUuid || nextEnvironments[0]?.uuid || ''); const object = nextObjects.find(item => item.uuid === requestedObjectUuid); if (object) setScope(object.objectReference.toUpperCase()) } catch { if (mounted.current && request === generation.current) setError(t('common.loadError')) } finally { if (mounted.current && request === generation.current) setLoading(false) } }, [modelUuid, projectUuid, requestedObjectUuid, t])
  useEffect(() => { void load() }, [load])
  const binding = useMemo(() => bindings.find((item) => item.logicalSchemaUuid === model?.logicalSchemaUuid && item.environmentUuid === environmentUuid), [bindings, environmentUuid, model?.logicalSchemaUuid])
  const physicalSchema = physical.find((item) => item.uuid === binding?.physicalSchemaUuid); const connection = connections.find((item) => item.uuid === physicalSchema?.connectionUuid)
  useEffect(() => { if (!preview) { setPrevious(null); return } const existing = objects.find((item) => item.objectReference.toLocaleUpperCase() === preview.name.toLocaleUpperCase()); if (!existing) { setPrevious(null); return } let active = true; void topologyApi.listSchemaSnapshots(projectUuid, existing.uuid).then((rows) => { if (active) setPrevious(rows[0] ?? null) }).catch(() => { if (active) setPrevious(null) }); return () => { active = false } }, [objects, preview, projectUuid])
  async function discover() { if (model?.reverseMode === 'CUSTOM_RKM' || !binding || !physicalSchema || !connection || discovering || importing) return; setDiscovering(true); setImportProgress(null); setResult(null); setPreview(null); setPrevious(null); setSelected(new Set()); setError(''); setNotice(''); try { const next = await topologyApi.discoverOracle(projectUuid, connection.uuid, physicalSchema.uuid, { tableName: scope.trim() || undefined, limit: 100 }); if (!mounted.current) return; setResult(next); setSelected(new Set()); setPreview(null) } catch (reason) { if (mounted.current) setError(reason instanceof Error ? reason.message : t('models.discoveryFailed')) } finally { if (mounted.current) setDiscovering(false) } }
  const toggle = (table: DiscoveryTable) => setSelected((current) => { const key = `${table.owner}.${table.name}`; const next = new Set(current); if (next.has(key)) next.delete(key); else next.add(key); return next })
  async function importSelected() {
    if (!binding || !physicalSchema || !connection || !result || discovering || importPending.current) return
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
        listObjects: () => topologyApi.listDataObjects(projectUuid, modelUuid),
        createObject: table => topologyApi.createDataObject(projectUuid, modelUuid, {
          submodelUuid: folderUuid || null,
          code: `${table.owner}_${table.name}`.replace(/[^A-Za-z0-9_]/g, '_').slice(0, 100).toUpperCase(),
          name: table.name, objectReference: table.name, type: table.type === 'VIEW' ? 'VIEW' : 'TABLO',
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
      if (mounted.current) setNotice(t('models.importedCount', { count }))
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
  const changes = preview ? compareMetadata(previous?.columns ?? [], preview.columns) : []
  const existingFor = (table: DiscoveryTable) => objects.find((item) => item.objectReference.toLocaleUpperCase() === table.name.toLocaleUpperCase())
  const technology = model.technologyCode ?? 'ORACLE'
  const contextReady = Boolean(binding && physicalSchema && connection)
  const discoverLabel = requestedObjectUuid ? (tr ? 'Metadata’yı Yenile' : 'Refresh Metadata') : t('models.discover')
  const importActions = result ? <><label className="metadata-folder-choice">{t('models.folder')}<FormSelect disabled={importing} value={folderUuid} onChange={(event) => setFolderUuid(event.target.value)}><option value="">{t('models.modelRoot')}</option>{folders.map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name}</option>)}</FormSelect></label><Button tone="primary" icon={<Save size={16} />} disabled={selected.size === 0} busy={importing} onClick={() => void importSelected()}>{t('models.importSelected')}</Button></> : undefined
  return <section className="page-stack connections-page models-page metadata-import-page"><Link className="connection-back-link" to={`/project/models/${modelUuid}`}><ArrowLeft size={16} />{model.name}</Link>
    <FeedbackToast message={error} tone="error" onClose={() => setError('')} /><FeedbackToast message={notice} onClose={() => setNotice('')} />
    <section className="connection-management-panel"><PageHeader icon={<ScanSearch />} eyebrow={`${databaseProviderVisual(technology).label} · ${model.code}`} title={requestedObjectUuid ? (tr ? 'Data Store Metadata Yenileme' : 'Refresh Data Store Metadata') : t('models.importMetadata')} description={t('models.importDescription')} />
    <form className="run-filters metadata-discovery-form" onSubmit={(event) => { event.preventDefault(); void discover() }}>
      <label><span>{t('models.environment')}</span><FormSelect aria-label={t('models.environment')} value={environmentUuid} disabled={discovering || importing} onChange={(event) => { setEnvironmentUuid(event.target.value); setImportProgress(null); setResult(null); setSelected(new Set()); setPreview(null); setPrevious(null) }}>{environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</FormSelect></label>
      {contextReady && <label><span>{t('models.discoveryScope')}</span><AntInput value={scope} disabled={discovering || importing} onChange={(event) => { setScope(event.target.value.toUpperCase()); setImportProgress(null); setResult(null); setSelected(new Set()); setPreview(null); setPrevious(null) }} placeholder={t('models.scopePlaceholder')} /></label>}
      {contextReady && <div className="run-filter-actions"><Button type="submit" tone="primary" icon={<ScanSearch size={16} />} busy={discovering} disabled={importing || model.reverseMode === 'CUSTOM_RKM'}>{discoverLabel}</Button></div>}
    </form>
    {contextReady && physicalSchema && connection ? <dl className="metadata-context-summary"><div><dt>{tr ? 'Teknoloji' : 'Technology'}</dt><dd><span className="provider-cell"><DatabaseProviderIcon databaseType={technology} /><span>{databaseProviderVisual(technology).label}</span></span></dd></div><div><dt>{t('models.connection')}</dt><dd>{connection.name}</dd></div><div><dt>{t('models.physicalSchema')}</dt><dd><code>{physicalSchema.schemaName}</code></dd></div><div><dt>{tr ? 'Keşif Modu' : 'Discovery Mode'}</dt><dd>{model.reverseMode === 'CUSTOM_RKM' ? 'RKM' : tr ? 'Standart JDBC' : 'Standard JDBC'}</dd></div></dl> : <div className="metadata-binding-missing"><strong>{t('models.bindingMissing')}</strong><Link to="/project/logical-schemas">{t('models.openBindings')}</Link></div>}
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
      {!result ? <AsyncState state="empty" title={contextReady ? (tr ? 'Henüz keşif çalıştırılmadı' : 'No discovery has run yet') : t('models.bindingMissing')} description={contextReady ? (tr ? 'Ortam ve kapsamı seçip kaynaktan nesneleri getirin.' : 'Pick the environment and scope, then fetch objects from the source.') : undefined} /> : result.tables.length === 0 ? <AsyncState state="empty" title={t('models.discoveryResult', { count: 0 })} /> : <ProgressiveRecords key={`${result.discoveredAt}:${scope}`} items={result.tables}>{(visible) => <DataGrid collectionTitle={`${t('models.discoveryResult', { count: result.tables.length })} · ${result.owner} · ${result.truncated ? t('models.partialResult') : t('models.completeScope')}`} collectionIcon={<Database />} toolbarActions={importActions} cardHeaderLeadingField="select" cardHeaderField="state" cardHiddenFields={['select', 'state']} headerFieldsInList view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="select"><span className="sr-only">{t('models.select')}</span></th><th data-field-key="name">{t('models.object')}</th><th data-field-key="type">{t('models.type')}</th><th data-field-key="columns">{t('models.columnCount')}</th><th data-field-key="state">{tr ? 'Katalog' : 'Catalog'}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
        <tbody>{visible.map((table) => { const key = `${table.owner}.${table.name}`; const existing = existingFor(table); return <tr key={key} data-connection-uuid={key} className={preview === table ? 'is-selected' : ''}>
          <td><AntCheckbox disabled={importing} checked={selected.has(key)} aria-label={t('models.selectObject', { name: table.name })} onClick={(event) => event.stopPropagation()} onChange={() => toggle(table)} /></td>
          <td><span className="connection-record-identity"><strong>{table.name}</strong><small>{table.owner}</small></span></td>
          <td><span className="model-object-kind">{table.type === 'VIEW' ? <Eye size={16} /> : <Table2 size={16} />}{table.type}</span></td>
          <td>{table.columns.length}</td>
          <td>{existing ? t('models.existingObject') : t('models.newObject')}</td>
          <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={table.name} editable={false} onClick={() => setPreview(table)} /></div></td>
        </tr> })}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>
    {preview && <RecordDetailDialog open title={<span className="connection-dialog-title">{preview.type === 'VIEW' ? <Eye size={17} aria-hidden="true" /> : <Table2 size={17} aria-hidden="true" />}{preview.name}</span>} readOnly onClose={() => setPreview(null)} className="connection-catalog-dialog">
      <section className="connection-detail-section metadata-preview"><header><strong>{preview.owner}.{preview.name}</strong><span>{previous ? t('models.existingObject') : t('models.newObject')}</span></header><MetadataDiff changes={changes} /></section>
    </RecordDetailDialog>}
  </section>
}
