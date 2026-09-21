import { ArrowLeft, CheckCircle2, CircleAlert, Database, Eye, Folder, Layers3, RefreshCw, ScanSearch, Table2 } from 'lucide-react'
import { useDocumentTab } from '../../app/DocumentTabsContext'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Tag, Tooltip } from 'antd'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { connectionStatusTagStyles } from '../connections/presentation'
import { DataObjectTable } from './DataObjectTable'
import { topologyApi, type DataObject, type LogicalSchema, type Model, type SchemaSnapshot, type Submodel } from '../topology/api'
import { CreateModelFolder } from './CreateModelFolder'
import { DataObjectFolderEditor } from './DataObjectFolderEditor'
import './models.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

export function ModelDetailPage() {
  const { modelUuid = '' } = useParams()
  const projectUuid = useCurrentProjectUuid()
  return <ModelDetailSession key={`${projectUuid}:${modelUuid}`} projectUuid={projectUuid} modelUuid={modelUuid} />
}

/** Data stores of one model in the shared catalog layout; the record detail (columns) opens in the central dialog. */
function ModelDetailSession({ projectUuid, modelUuid }: { projectUuid: string; modelUuid: string }) {
  const { t, i18n } = useTranslation(); const [params, setParams] = useSearchParams(); const navigate = useNavigate()
  const tr = i18n.language === 'tr'
  const generation = useRef(0)
  const { can } = useProjectAccess()
  const canDiscover = can('KATALOG_KESFET')
  const [view, setView] = useCollectionView('akis:data-objects:view')
  const [model, setModel] = useState<Model | null>(null); const [logical, setLogical] = useState<LogicalSchema | null>(null); const [submodels, setSubmodels] = useState<Submodel[]>([]); const [objects, setObjects] = useState<DataObject[]>([]); const [snapshots, setSnapshots] = useState<SchemaSnapshot[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const [snapshotLoading, setSnapshotLoading] = useState(false); const [snapshotError, setSnapshotError] = useState(false)
  const folderUuid = params.get('folder'); const folder = submodels.find(item => item.uuid === folderUuid)
  useDocumentTab(model ? { path: `/project/models/${encodeURIComponent(model.uuid)}`, title: model.name, subtitle: model.code, kind: 'MODEL' } : null)
  const query = params.get('q') ?? ''
  const visibleObjects = useMemo(() => objects
    .filter(item => folderUuid ? item.submodelUuid === folderUuid : !item.submodelUuid)
    .filter(item => `${item.name} ${item.objectReference} ${item.code}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language))), [objects, folderUuid, query, i18n.language])
  const visibleFolders = submodels.filter(item => folderUuid ? item.parentUuid === folderUuid : !item.parentUuid)
  const selectedUuid = params.get('object'); const selected = objects.find((item) => item.uuid === selectedUuid)
  const load = useCallback(async () => {
    const request = ++generation.current
    setLoading(true); setError('')
    try {
      const [nextModel, nextSubmodels, nextObjects, schemas] = await Promise.all([
        topologyApi.getModel(projectUuid, modelUuid), topologyApi.listSubmodels(projectUuid, modelUuid),
        topologyApi.listDataObjects(projectUuid, modelUuid), topologyApi.listLogicalSchemas(projectUuid),
      ])
      if (request !== generation.current) return
      setModel(nextModel); setSubmodels(nextSubmodels); setObjects(nextObjects)
      setLogical(schemas.find(item => item.uuid === nextModel.logicalSchemaUuid) ?? null)
    } catch { if (request === generation.current) setError(t('common.loadError')) }
    finally { if (request === generation.current) setLoading(false) }
  }, [modelUuid, projectUuid, t])
  useEffect(() => { void load(); return () => { generation.current += 1 } }, [load])
  useEffect(() => { if (!selected) { setSnapshots([]); return } setSnapshots([]); setSnapshotLoading(true); setSnapshotError(false); let active = true; void topologyApi.listSchemaSnapshots(projectUuid, selected.uuid).then((rows) => { if (active) setSnapshots(rows) }).catch(() => { if (active) setSnapshotError(true) }).finally(() => { if (active) setSnapshotLoading(false) }); return () => { active = false } }, [projectUuid, selected])
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error || !model) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const latest = snapshots[0]
  const typeLabel = (type: string) => ['TABLE', 'TABLO'].includes(type) ? t('models.typeTable') : type === 'VIEW' ? t('models.typeView') : type.toLocaleLowerCase(i18n.language).replaceAll('_', ' ').replace(/(^|\s)\S/g, (value) => value.toLocaleUpperCase(i18n.language))
  const isView = (type: string) => type === 'VIEW'
  const setParam = (changes: Record<string, string | null>, replace = true) => { const next = new URLSearchParams(params); for (const [key, value] of Object.entries(changes)) { if (value) next.set(key, value); else next.delete(key) } setParams(next, { replace }) }
  const selectObject = (uuid: string) => setParam({ object: uuid })
  const closeObject = () => setParam({ object: null })
  const applyQuery = (next: string) => setParam({ q: next || null }, false)
  const statusTag = (status: string) => { const ok = status === 'AKTIF'; const tone = ok ? 'success' : 'neutral'; return <Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{ok ? t('models.statusActive') : t('models.statusInactive')}</span></Tag> }
  const importLink = canDiscover ? <Link className="button primary" to={`/project/models/${modelUuid}/import${folderUuid ? `?folder=${encodeURIComponent(folderUuid)}` : ''}`}><ScanSearch size={16} />{t('models.importMetadata')}</Link> : null
  const toolbar = <>{canDiscover && <CreateModelFolder projectUuid={projectUuid} modelUuid={modelUuid} folders={submodels} parentUuid={folderUuid ?? undefined} onCreated={() => void load()} />}{importLink}</>

  return <section className="page-stack connections-page models-page model-detail-page"><Link className="connection-back-link" to="/project/models"><ArrowLeft size={16} />{t('models.title')}</Link>
    <section className="connection-management-panel"><PageHeader icon={<Layers3 />} eyebrow={`${model.code} · ${logical?.name ?? t('models.notConfigured')}`} title={folder ? `${model.name} / ${folder.name}` : model.name} description={model.description ?? t('models.detailDescription')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Data store adı veya nesne referansına göre ara' : 'Search by data store name or object reference'} /></section>
    <SummaryStrip ariaLabel={tr ? 'Model özeti' : 'Model summary'} items={[
      { label: 'Data Store', value: objects.length, icon: <Database />, tone: 'info' },
      { label: t('models.typeTable'), value: objects.filter(item => !isView(item.type)).length, icon: <Table2 />, tone: 'teal' },
      { label: t('models.typeView'), value: objects.filter(item => isView(item.type)).length, icon: <Eye />, tone: 'neutral' },
      { label: tr ? 'Klasör' : 'Folders', value: submodels.length, icon: <Folder />, tone: 'neutral' },
      { label: t('models.statusActive'), value: objects.filter(item => item.status === 'AKTIF').length, icon: <CheckCircle2 />, tone: 'success' },
    ]} />
    <section className="connections-records">
      <nav className="model-folder-navigation" aria-label={tr ? 'Model Klasörleri' : 'Model Folders'}>
        {folderUuid && <Button icon={<ArrowLeft size={16} />} onClick={() => setParams(folder?.parentUuid ? { folder: folder.parentUuid } : {})}>{tr ? 'Üst Klasör' : 'Parent Folder'}</Button>}
        {visibleFolders.map(item => <Button key={item.uuid} icon={<Folder size={16} className="project-folder-icon" />} onDoubleClick={() => setParams({ folder: item.uuid })} onKeyDown={event => { if (event.key === 'Enter') setParams({ folder: item.uuid }) }}>{item.name}</Button>)}
      </nav>
      {selectedUuid && !selected ? <div className="error-banner" role="alert">{tr ? 'Data Store Bulunamadı' : 'Data Store Not Found'}</div> : null}
      {visibleObjects.length === 0 ? <AsyncState state="empty" title={t('models.noDataObjects')} action={importLink ?? undefined} /> : <ProgressiveRecords key={`${folderUuid ?? ''}:${query}`} items={visibleObjects}>{(visible) => <DataGrid auditKind="data-objects" auditInFooter collectionTitle={tr ? 'Data Store Listesi' : 'Data Store List'} collectionIcon={<Database />} toolbarActions={toolbar} cardHeaderLeadingField="type" cardHeaderField="status" cardHiddenFields={['type', 'status']} headerFieldsInList view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="type">{tr ? 'Tür' : 'Type'}</th><th data-field-key="object">Data Store</th><th data-field-key="reference">{tr ? 'Nesne Referansı' : 'Object Reference'}</th><th data-field-key="status">{tr ? 'Durum' : 'Status'}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
        <tbody>{visible.map(object => <tr key={object.uuid} data-connection-uuid={object.uuid} tabIndex={0} onDoubleClick={() => selectObject(object.uuid)} onKeyDown={event => { if (event.key === 'Enter' && event.target === event.currentTarget) selectObject(object.uuid) }} className={selected?.uuid === object.uuid ? 'is-selected' : undefined}>
          <td><span className="model-object-kind">{isView(object.type) ? <Eye size={16} /> : <Table2 size={16} />}{typeLabel(object.type)}</span></td>
          <td><span className="connection-record-identity"><strong>{object.name}</strong><small>{object.code}</small></span></td>
          <td><code>{object.objectReference}</code></td>
          <td>{statusTag(object.status)}</td>
          <td className="row-actions"><div className="connection-row-actions">{canDiscover && <Tooltip title={tr ? 'Metadata yenile' : 'Refresh metadata'}><Button aria-label={`${tr ? 'Metadata yenile' : 'Refresh metadata'}: ${object.name}`} icon={<RefreshCw size={15} />} onClick={() => navigate(`/project/models/${modelUuid}/import?object=${encodeURIComponent(object.uuid)}`)} /></Tooltip>}<RecordActionButton name={object.name} editable={false} onClick={() => selectObject(object.uuid)} /></div></td>
        </tr>)}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>
    {selected && <RecordDetailDialog open title={<span className="connection-dialog-title">{isView(selected.type) ? <Eye size={17} aria-hidden="true" /> : <Table2 size={17} aria-hidden="true" />}{typeLabel(selected.type)} · {selected.code}</span>} readOnly onClose={closeObject} className="connection-catalog-dialog">
      <section className="page-stack connection-detail-page model-object-detail">
        <header><div><span>{typeLabel(selected.type)}</span><h2>{selected.name}</h2><code>{selected.objectReference}</code></div>{statusTag(selected.status)}</header>
        <div className="model-schema-context"><Database size={18} /><div><strong>{tr ? 'Mantıksal Şema' : 'Logical Schema'}</strong><span>{logical?.name ?? (tr ? 'Seçilmedi' : 'Not Selected')}</span><small>{tr ? 'Veri nesneleri bu modelin mantıksal şemasını kullanır. Fiziksel şema, çalıştırma ortamı eşlemesinden belirlenir.' : 'Data objects inherit this model’s logical schema. The physical schema is resolved from the execution environment binding.'}</small></div></div>
        {canDiscover && selected.status === 'AKTIF' && model.status === 'AKTIF' && <DataObjectFolderEditor key={`${selected.uuid}:${selected.version}`} projectUuid={projectUuid} object={selected} folders={submodels} onMoved={next => { setObjects(current => current.map(item => item.uuid === next.uuid ? next : item)); setParams({ ...(next.submodelUuid ? { folder: next.submodelUuid } : {}), object: next.uuid }, { replace: true }) }} />}
        {snapshotLoading ? <AsyncState state="loading" title={t('common.loading')} /> : snapshotError ? <AsyncState state="error" title={t('common.loadError')} /> : latest ? <><div className="metadata-evidence"><span>{t('models.discoveredAt')}</span><strong>{new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(latest.discoveredAt))}</strong><span>{t('models.engineVersion')}</span><strong>{latest.engineVersion}</strong></div><DataObjectTable columns={latest.columns} protection={{ projectUuid, modelUuid, objectUuid: selected.uuid, editable: canDiscover && selected.status === 'AKTIF' }} /></> : <AsyncState state="empty" title={t('models.noSnapshot')} description={t('models.noSnapshotHint')} />}
      </section>
    </RecordDetailDialog>}
  </section>
}
