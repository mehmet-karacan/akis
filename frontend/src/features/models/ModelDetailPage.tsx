import { ArrowLeft, CheckCircle2, ChevronRight, CircleAlert, Database, Eye, Folder, Layers3, RefreshCw, Table2 } from 'lucide-react'
import { useDocumentTab } from '../../app/DocumentTabsContext'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Tag, Tooltip } from 'antd'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { Select } from '../../core/ui/Select'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { connectionStatusTagStyles } from '../connections/presentation'
import { DataObjectTable } from './DataObjectTable'
import { MetadataImportPage } from './MetadataImportPage'
import { topologyApi, type DataObject, type LogicalSchema, type Model, type SchemaSnapshot, type Submodel } from '../topology/api'
import { CreateModelFolder } from './CreateModelFolder'
import { DataObjectFolderEditor } from './DataObjectFolderEditor'
import './models.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import { matchesCatalogReference, modelPath, resolveModelReference } from './modelRoutes'
import { DataObjectTypeIcon, dataObjectTypeLabel } from './DataObjectTypeIcon'

export function ModelDetailPage() {
  const { modelUuid: modelReference = '' } = useParams()
  const projectUuid = useCurrentProjectUuid()
  return <ModelDetailSession key={`${projectUuid}:${modelReference}`} projectUuid={projectUuid} modelReference={modelReference} />
}

/** Data stores of one model in the shared catalog layout; the record detail (columns) opens in the central dialog. */
function ModelDetailSession({ projectUuid, modelReference }: { projectUuid: string; modelReference: string }) {
  const { t, i18n } = useTranslation(); const [params, setParams] = useSearchParams()
  const tr = i18n.language === 'tr'
  const generation = useRef(0)
  const { can } = useProjectAccess()
  const canDiscover = can('KATALOG_KESFET')
  const [view, setView] = useCollectionView('akis:data-objects:view')
  const [model, setModel] = useState<Model | null>(null); const [logical, setLogical] = useState<LogicalSchema | null>(null); const [submodels, setSubmodels] = useState<Submodel[]>([]); const [objects, setObjects] = useState<DataObject[]>([]); const [snapshots, setSnapshots] = useState<SchemaSnapshot[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const [snapshotLoading, setSnapshotLoading] = useState(false); const [snapshotError, setSnapshotError] = useState(false)
  const folderReference = params.get('folder'); const folder = submodels.find(item => matchesCatalogReference(item, folderReference)); const folderUuid = folder?.uuid
  const reverseRef = useRef<HTMLElement | null>(null)
  useDocumentTab(model ? { path: modelPath(model), title: model.name, subtitle: model.code, kind: 'MODEL' } : null)
  const query = params.get('q') ?? ''
  const typeFilter = params.get('type') ?? 'ALL'
  const statusFilter = params.get('status') ?? 'ALL'
  const visibleObjects = useMemo(() => objects
    .filter(item => folderUuid ? item.submodelUuid === folderUuid : !item.submodelUuid)
    .filter(item => typeFilter === 'ALL' || (typeFilter === 'TABLE' ? ['TABLE', 'TABLO'].includes(item.type) : item.type === typeFilter))
    .filter(item => statusFilter === 'ALL' || item.status === statusFilter)
    .filter(item => `${item.name} ${item.objectReference} ${item.code}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language))), [objects, folderUuid, query, typeFilter, statusFilter, i18n.language])
  const visibleFolders = submodels.filter(item => folderUuid ? item.parentUuid === folderUuid : !item.parentUuid)
  const folderTrail: Submodel[] = []
  const visitedFolders = new Set<string>()
  let trailItem = folder
  while (trailItem && !visitedFolders.has(trailItem.uuid)) {
    folderTrail.unshift(trailItem)
    visitedFolders.add(trailItem.uuid)
    trailItem = submodels.find(item => item.uuid === trailItem?.parentUuid)
  }
  const selectedReference = params.get('object'); const selected = objects.find(item => matchesCatalogReference(item, selectedReference))
  const load = useCallback(async () => {
    const request = ++generation.current
    setLoading(true); setError('')
    try {
      const nextModel = await resolveModelReference(projectUuid, modelReference)
      const [nextSubmodels, nextObjects, schemas] = await Promise.all([
        topologyApi.listSubmodels(projectUuid, nextModel.uuid), topologyApi.listDataObjects(projectUuid, nextModel.uuid), topologyApi.listLogicalSchemas(projectUuid),
      ])
      if (request !== generation.current) return
      setModel(nextModel); setSubmodels(nextSubmodels); setObjects(nextObjects)
      setLogical(schemas.find(item => item.uuid === nextModel.logicalSchemaUuid) ?? null)
    } catch { if (request === generation.current) setError(t('common.loadError')) }
    finally { if (request === generation.current) setLoading(false) }
  }, [modelReference, projectUuid, t])
  useEffect(() => { void load(); return () => { generation.current += 1 } }, [load])
  useEffect(() => { if (params.get('refresh') || params.get('tab') === 'reverse') reverseRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) }, [params, loading])
  useEffect(() => { if (!selected) { setSnapshots([]); return } setSnapshots([]); setSnapshotLoading(true); setSnapshotError(false); let active = true; void topologyApi.listSchemaSnapshots(projectUuid, selected.uuid).then((rows) => { if (active) setSnapshots(rows) }).catch(() => { if (active) setSnapshotError(true) }).finally(() => { if (active) setSnapshotLoading(false) }); return () => { active = false } }, [projectUuid, selected])
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error || !model) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const latest = snapshots[0]
  const typeLabel = (type: string) => dataObjectTypeLabel(type, tr)
  const setParam = (changes: Record<string, string | null>, replace = true) => { const next = new URLSearchParams(params); for (const [key, value] of Object.entries(changes)) { if (value) next.set(key, value); else next.delete(key) } setParams(next, { replace }) }
  const selectObject = (object: DataObject) => setParam({ object: object.code })
  const closeObject = () => setParam({ object: null })
  const applyQuery = (next: string) => setParam({ q: next || null }, false)
  const statusTag = (status: string) => { const ok = status === 'AKTIF'; const tone = ok ? 'success' : 'neutral'; return <Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{ok ? t('models.statusActive') : t('models.statusInactive')}</span></Tag> }
  const headerActions = canDiscover ? <CreateModelFolder projectUuid={projectUuid} modelUuid={model.uuid} folders={submodels} parentUuid={folderUuid} onCreated={() => void load()} /> : undefined
  return <section className="page-stack connections-page models-page model-detail-page"><Link className="connection-back-link" to="/project/models"><ArrowLeft size={16} />{t('models.title')}</Link>
    <section className="connection-management-panel"><PageHeader icon={<Layers3 />} eyebrow={`${model.code} · ${logical?.name ?? t('models.notConfigured')}`} title={folder ? `${model.name} / ${folder.name}` : model.name} description={model.description ?? t('models.detailDescription')} actions={headerActions} />
    <QueryFilter onApply={applyQuery} onReset={() => setParams({})} placeholder={tr ? 'Data store adı, kısa kod veya nesne referansına göre ara' : 'Search by data store name, short code or object reference'}>
      <label><span>{tr ? 'Tür' : 'Type'}</span><Select value={typeFilter} onChange={event => setParam({ type: event.target.value === 'ALL' ? null : event.target.value })}><option value="ALL">{tr ? 'Tüm türler' : 'All types'}</option><option value="TABLE">{t('models.typeTable')}</option><option value="VIEW">{t('models.typeView')}</option><option value="MATERIALIZED_VIEW">{tr ? 'Materyalize görünüm' : 'Materialized view'}</option><option value="SYNONYM">{tr ? 'Eş anlamlı' : 'Synonym'}</option></Select></label>
      <label><span>{tr ? 'Durum' : 'Status'}</span><Select value={statusFilter} onChange={event => setParam({ status: event.target.value === 'ALL' ? null : event.target.value })}><option value="ALL">{tr ? 'Tüm durumlar' : 'All statuses'}</option><option value="AKTIF">{tr ? 'Etkin' : 'Active'}</option><option value="PASIF">{tr ? 'Pasif' : 'Inactive'}</option></Select></label>
    </QueryFilter></section>
    <SummaryStrip ariaLabel={tr ? 'Model özeti' : 'Model summary'} items={[
      { label: 'Data Store', value: objects.length, icon: <Database />, tone: 'info' },
      { label: t('models.typeTable'), value: objects.filter(item => ['TABLE', 'TABLO'].includes(item.type)).length, icon: <Table2 />, tone: 'teal' },
      { label: t('models.typeView'), value: objects.filter(item => item.type === 'VIEW').length, icon: <Eye />, tone: 'neutral' },
      { label: tr ? 'Materyalize Görünüm' : 'Materialized Views', value: objects.filter(item => item.type === 'MATERIALIZED_VIEW').length, icon: <Layers3 />, tone: 'warning' },
      { label: tr ? 'Eş Anlamlı' : 'Synonyms', value: objects.filter(item => item.type === 'SYNONYM').length, icon: <Database />, tone: 'success' },
      { label: tr ? 'Klasör' : 'Folders', value: submodels.length, icon: <Folder />, tone: 'neutral' },
      { label: t('models.statusActive'), value: objects.filter(item => item.status === 'AKTIF').length, icon: <CheckCircle2 />, tone: 'success' },
    ]} />
    <div className="model-integrated-workspace"><section className="connections-records">
      <nav className="model-folder-navigation" aria-label={tr ? 'Model Klasörleri' : 'Model Folders'}>
        <div className="model-folder-breadcrumb"><Button onClick={() => setParam({ folder: null, object: null })}>{tr ? 'Model Kökü' : 'Model Root'}</Button>{folderTrail.map(item => <span key={item.uuid} className="model-folder-crumb"><ChevronRight size={14} aria-hidden="true" /><Button onClick={() => setParam({ folder: item.code, object: null })}>{item.name}</Button></span>)}</div>
        {visibleFolders.length > 0 && <div className="model-folder-children">{visibleFolders.map(item => <Button key={item.uuid} icon={<Folder size={16} className="project-folder-icon" />} onClick={() => setParam({ folder: item.code, object: null })}>{item.name}</Button>)}</div>}
      </nav>
      {selectedReference && !selected ? <div className="error-banner" role="alert">{tr ? 'Data Store Bulunamadı' : 'Data Store Not Found'}</div> : null}
      {visibleObjects.length === 0 ? <AsyncState state="empty" title={t('models.noDataObjects')} /> : <ProgressiveRecords key={`${folderUuid ?? ''}:${query}`} items={visibleObjects}>{(visible) => <DataGrid collectionTitle={tr ? 'Data Store Listesi' : 'Data Store List'} collectionIcon={<Database />} cardHeaderLeadingField="type" cardHeaderField="status" cardHiddenFields={['type', 'status']} view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="type">{tr ? 'Tür' : 'Type'}</th><th data-field-key="object">Data Store</th><th data-field-key="reference">{tr ? 'Nesne Referansı' : 'Object Reference'}</th><th data-field-key="status">{tr ? 'Durum' : 'Status'}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
        <tbody>{visible.map(object => <tr key={object.uuid} data-connection-uuid={object.uuid} tabIndex={0} onDoubleClick={() => selectObject(object)} onKeyDown={event => { if (event.key === 'Enter' && event.target === event.currentTarget) selectObject(object) }} className={selected?.uuid === object.uuid ? 'is-selected' : undefined}>
          <td><span className="model-object-kind"><DataObjectTypeIcon type={object.type} />{typeLabel(object.type)}</span></td>
          <td><span className="connection-record-identity"><strong>{object.name}</strong><small>{object.code}</small></span></td>
          <td><code>{object.objectReference}</code></td>
          <td>{statusTag(object.status)}</td>
           <td className="row-actions"><div className="connection-row-actions">{canDiscover && <Tooltip title={tr ? 'Metadata yenile' : 'Refresh metadata'}><Button aria-label={`${tr ? 'Metadata yenile' : 'Refresh metadata'}: ${object.name}`} icon={<RefreshCw size={15} />} onClick={() => setParam({ refresh: object.code, object: null, folder: submodels.find(item => item.uuid === object.submodelUuid)?.code ?? null }, false)} /></Tooltip>}<RecordActionButton name={object.name} editable={false} onClick={() => selectObject(object)} /></div></td>
        </tr>)}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>{canDiscover && <aside className="model-integrated-reverse" ref={reverseRef} aria-label={tr ? 'Reverse Engineer çalışma alanı' : 'Reverse Engineer workspace'}><MetadataImportPage embedded initialModel={model} onImported={() => { void topologyApi.listDataObjects(projectUuid, model.uuid).then(setObjects) }} /></aside>}</div>
    {selected && <RecordDetailDialog open title={<span className="connection-dialog-title"><DataObjectTypeIcon type={selected.type} />{typeLabel(selected.type)} · {selected.code}</span>} readOnly onClose={closeObject} className="connection-catalog-dialog">
      <section className="page-stack connection-detail-page model-object-detail">
        <header className="model-object-hero"><div><span className="model-object-eyebrow">{model.name}{folder ? ` / ${folder.name}` : ''} / {typeLabel(selected.type)}</span><h2>{selected.name}</h2>{selected.objectReference !== selected.name && <code>{selected.objectReference}</code>}<p>{tr ? 'Kolon yapısı, veri tipleri ve koruma işaretleri' : 'Column structure, data types and protection markings'}</p></div>{statusTag(selected.status)}</header>
        <div className="model-object-overview"><div className="model-schema-context"><Database size={18} /><div><strong>{tr ? 'Mantıksal Şema' : 'Logical Schema'}</strong><span>{logical?.name ?? (tr ? 'Seçilmedi' : 'Not Selected')}</span><small>{tr ? 'Fiziksel şema seçilen çalışma ortamında çözümlenir.' : 'The physical schema is resolved in the selected execution environment.'}</small></div></div>
          {canDiscover && selected.status === 'AKTIF' && model.status === 'AKTIF' && <DataObjectFolderEditor key={`${selected.uuid}:${selected.version}`} projectUuid={projectUuid} object={selected} folders={submodels} onMoved={next => { setObjects(current => current.map(item => item.uuid === next.uuid ? next : item)); setParams({ ...(next.submodelUuid ? { folder: next.submodelUuid } : {}), object: next.uuid }, { replace: true }) }} />}
        </div>
        {snapshotLoading ? <AsyncState state="loading" title={t('common.loading')} /> : snapshotError ? <AsyncState state="error" title={t('common.loadError')} /> : latest ? <><div className="metadata-evidence"><span>{t('models.discoveredAt')}</span><strong>{new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(latest.discoveredAt))}</strong><span>{t('models.engineVersion')}</span><strong>{latest.engineVersion}</strong></div><DataObjectTable columns={latest.columns} protection={{ projectUuid, modelUuid: model.uuid, objectUuid: selected.uuid, editable: canDiscover && selected.status === 'AKTIF' }} /></> : <AsyncState state="empty" title={t('models.noSnapshot')} description={t('models.noSnapshotHint')} />}
      </section>
    </RecordDetailDialog>}
  </section>
}
