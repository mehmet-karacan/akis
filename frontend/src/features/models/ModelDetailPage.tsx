import { ArrowLeft, Download, Database } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, PageHeader, StatusBadge } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { DataObjectTable } from './DataObjectTable'
import { topologyApi, type DataObject, type LogicalSchema, type Model, type SchemaSnapshot, type Submodel } from '../topology/api'
import { CreateModelFolder } from './CreateModelFolder'
import './models.css'

export function ModelDetailPage() {
  const { modelUuid = '' } = useParams(); const projectUuid = useCurrentProjectUuid(); const { t, i18n } = useTranslation(); const [params] = useSearchParams()
  const { can } = useProjectAccess()
  const [model, setModel] = useState<Model | null>(null); const [logical, setLogical] = useState<LogicalSchema | null>(null); const [submodels, setSubmodels] = useState<Submodel[]>([]); const [objects, setObjects] = useState<DataObject[]>([]); const [snapshots, setSnapshots] = useState<SchemaSnapshot[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState('')
  const [snapshotLoading, setSnapshotLoading] = useState(false); const [snapshotError, setSnapshotError] = useState(false)
  const selectedUuid = params.get('object'); const selected = objects.find((item) => item.uuid === selectedUuid) ?? objects[0]
  const load = useCallback(async () => { setLoading(true); setError(''); try { const [nextModel, nextSubmodels, nextObjects, schemas] = await Promise.all([topologyApi.getModel(projectUuid, modelUuid), topologyApi.listSubmodels(projectUuid, modelUuid), topologyApi.listDataObjects(projectUuid, modelUuid), topologyApi.listLogicalSchemas(projectUuid)]); setModel(nextModel); setSubmodels(nextSubmodels); setObjects(nextObjects); setLogical(schemas.find((item) => item.uuid === nextModel.logicalSchemaUuid) ?? null) } catch { setError(t('common.loadError')) } finally { setLoading(false) } }, [modelUuid, projectUuid, t])
  useEffect(() => { void load() }, [load])
  useEffect(() => { if (!selected) { setSnapshots([]); return } setSnapshots([]); setSnapshotLoading(true); setSnapshotError(false); let active = true; void topologyApi.listSchemaSnapshots(projectUuid, selected.uuid).then((rows) => { if (active) setSnapshots(rows) }).catch(() => { if (active) setSnapshotError(true) }).finally(() => { if (active) setSnapshotLoading(false) }); return () => { active = false } }, [projectUuid, selected])
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (!model) return <AsyncState state="error" title={error || t('common.loadError')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const latest = snapshots[0]
  const statusLabel = selected?.status === 'AKTIF' ? t('models.statusActive') : t('models.statusInactive')
  const typeLabel = (type: string) => ['TABLE', 'TABLO'].includes(type) ? t('models.typeTable') : type === 'VIEW' ? t('models.typeView') : type.toLocaleLowerCase(i18n.language).replaceAll('_', ' ').replace(/(^|\s)\S/g, (value) => value.toLocaleUpperCase(i18n.language))
  return <section className="page-stack models-page"><Link className="connection-back-link" to={`/projects/${projectUuid}/models`}><ArrowLeft size={16} />{t('models.title')}</Link>
    <PageHeader eyebrow={`${model.code} · ${logical?.name ?? '—'}`} title={model.name} description={model.description ?? t('models.detailDescription')} actions={<>{can('BAGLANTI_YONET') && <CreateModelFolder projectUuid={projectUuid} modelUuid={modelUuid} folders={submodels} onCreated={() => void load()} />}{can('KATALOG_KESFET') ? <Link className="button primary" to={`/projects/${projectUuid}/models/${modelUuid}/import`}><Download size={16} />{t('models.importMetadata')}</Link> : null}</>} />
    <div className="model-detail-layout model-detail-layout--single">
      <section className="model-object-detail"><div className="model-schema-context"><Database size={18} /><div><strong>{i18n.language === 'tr' ? 'Mantıksal Şema' : 'Logical Schema'}</strong><span>{logical?.name ?? (i18n.language === 'tr' ? 'Seçilmedi' : 'Not Selected')}</span><small>{i18n.language === 'tr' ? 'Veri nesneleri bu modelin mantıksal şemasını kullanır. Fiziksel şema, çalıştırma ortamı eşlemesinden belirlenir.' : 'Data objects inherit this model’s logical schema. The physical schema is resolved from the execution environment binding.'}</small></div></div>{!selected ? <AsyncState state="empty" title={t('models.noDataObjects')} /> : <><header><div><span>{typeLabel(selected.type)}</span><h2>{selected.name}</h2><code>{selected.objectReference}</code></div><StatusBadge tone={selected.status === 'AKTIF' ? 'success' : 'neutral'}>{statusLabel}</StatusBadge></header>{snapshotLoading ? <AsyncState state="loading" title={t('common.loading')} /> : snapshotError ? <AsyncState state="error" title={t('common.loadError')} /> : latest ? <><div className="metadata-evidence"><span>{t('models.discoveredAt')}</span><strong>{new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(latest.discoveredAt))}</strong><span>{t('models.engineVersion')}</span><strong>{latest.engineVersion}</strong></div><DataObjectTable columns={latest.columns} /></> : <AsyncState state="empty" title={t('models.noSnapshot')} description={t('models.noSnapshotHint')} />}</>}</section>
    </div>
  </section>
}
