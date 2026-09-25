import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput, Popconfirm, Tabs, Tag, Tooltip } from 'antd'
import { Boxes, CheckCircle2, CircleAlert, Database, FileText, Layers3, Plus, ScanSearch, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, PageHeader, RecordActionButton, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { RecordAuditFields } from '../../core/ui/RecordAuditFields'
import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi, type Environment, type LogicalSchema, type Model } from '../topology/api'
import { definitionsApi } from '../definitions/api'
import type { Definition } from '../definitions/types'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { connectionStatusTagStyles } from '../connections/presentation'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import './models.css'
import { modelPath } from './modelRoutes'

type FormState = {
  name: string; code: string; description: string; technologyCode: string
  logicalSchemaUuid: string; reverseEnvironmentUuid: string
  reverseMode: 'STANDARD' | 'CUSTOM_RKM'; rkmDefinitionUuid: string
}
const emptyForm = (): FormState => ({ name: '', code: '', description: '', technologyCode: 'ORACLE', logicalSchemaUuid: '', reverseEnvironmentUuid: '', reverseMode: 'STANDARD', rkmDefinitionUuid: '' })

export function ModelsPage() {
  const [notice, setNotice] = useState('')
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [view, setView] = useCollectionView('akis:models:view')
  const { can } = useProjectAccess()
  const canManage = can('KATALOG_KESFET')
  const [models, setModels] = useState<Model[]>([])
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [rkms, setRkms] = useState<Definition[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<Model | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const audit = useRecordAudit('models', models.map(model => model.version).join(','))

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [nextModels, nextSchemas, nextEnvironments, nextRkms] = await Promise.all([
        topologyApi.listModels(projectUuid), topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid), definitionsApi.listDefinitions(projectUuid, 'KNOWLEDGE_MODULE'),
      ])
      setModels(nextModels); setLogicalSchemas(nextSchemas); setEnvironments(nextEnvironments); setRkms(nextRkms)
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])
  useEffect(() => { void load() }, [load])

  const query = searchParams.get('q') ?? ''
  const applyQuery = (next: string) => { const params = new URLSearchParams(searchParams); if (next) params.set('q', next); else params.delete('q'); setSearchParams(params) }
  const filtered = useMemo(() => {
    const text = query.trim().toLocaleLowerCase(i18n.language)
    return (text ? models.filter(model => `${model.name} ${model.code} ${model.technologyCode}`.toLocaleLowerCase(i18n.language).includes(text)) : models)
      .slice().sort((a, b) => a.name.localeCompare(b.name, i18n.language))
  }, [i18n.language, models, query])

  const showCreate = () => {
    setEditing(null)
    setForm({ ...emptyForm(), logicalSchemaUuid: logicalSchemas[0]?.uuid ?? '', technologyCode: logicalSchemas[0]?.databaseType || 'ORACLE', reverseEnvironmentUuid: environments[0]?.uuid ?? '' })
    setOpen(true)
  }
  const showEdit = (model: Model) => {
    setEditing(model)
    setForm({ name: model.name, code: model.code, description: model.description ?? '', technologyCode: model.technologyCode || 'ORACLE', logicalSchemaUuid: model.logicalSchemaUuid, reverseEnvironmentUuid: model.reverseEnvironmentUuid ?? '', reverseMode: model.reverseMode ?? 'STANDARD', rkmDefinitionUuid: model.rkmDefinitionUuid ?? '' })
    setOpen(true)
  }
  useEffect(() => {
    const editUuid = searchParams.get('edit')
    if (!loading && editUuid && !open) {
      const model = models.find(item => item.uuid === editUuid)
      if (model) showEdit(model)
    }
  }, [loading, models, open, searchParams])
  const closeEditor = () => { setOpen(false); if (searchParams.has('edit')) { const next = new URLSearchParams(searchParams); next.delete('edit'); setSearchParams(next, { replace: true }) } }
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm(current => ({ ...current, [key]: value }))

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!canManage || busy) return; setBusy(true); setError('')
    const body = { logicalSchemaUuid: form.logicalSchemaUuid, technologyCode: form.technologyCode, reverseEnvironmentUuid: form.reverseEnvironmentUuid || null, reverseMode: form.reverseMode, rkmDefinitionUuid: form.reverseMode === 'CUSTOM_RKM' ? form.rkmDefinitionUuid : null, reverseOptions: editing?.reverseMode === form.reverseMode && (form.reverseMode !== 'CUSTOM_RKM' || editing.rkmDefinitionUuid === form.rkmDefinitionUuid) ? editing.reverseOptions ?? {} : {}, name: form.name.trim(), description: form.description.trim() || null }
    try {
      if (editing) await topologyApi.updateModel(projectUuid, editing.uuid, { ...body, expectedVersion: editing.version })
      else await topologyApi.createModel(projectUuid, { ...body, code: form.code.trim().toUpperCase() })
      closeEditor(); setNotice(tr ? 'Model kaydedildi.' : 'Model saved.'); window.dispatchEvent(new Event('akis:models-changed')); await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  async function removeModel() {
    if (!editing || !canManage || busy) return
    setBusy(true); setError('')
    try {
      await topologyApi.deleteModel(projectUuid, editing.uuid, editing.version)
      closeEditor(); setNotice(tr ? 'Model silindi.' : 'Model deleted.'); window.dispatchEvent(new Event('akis:models-changed')); await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  const formatDate = (value?: string | null) => value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : t('models.notImported')
  const addButton = canManage ? <Button tone="primary" icon={<Plus size={16} />} disabled={logicalSchemas.length === 0} onClick={showCreate}>{t('models.create')}</Button> : undefined
  const schemaOf = (model: Model) => logicalSchemas.find(schema => schema.uuid === model.logicalSchemaUuid)
  return <section className="page-stack connections-page models-page"><FeedbackToast message={notice} onClose={() => setNotice('')} />
    <section className="connection-management-panel"><PageHeader icon={<Layers3 />} eyebrow={t('models.eyebrow')} title={t('models.title')} description={t('models.description')} />
    <QueryFilter onApply={applyQuery} placeholder={t('models.searchPlaceholder')} /></section>
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    <SummaryStrip ariaLabel={t('models.title')} items={[
      { label: tr ? 'Toplam Model' : 'Total Models', value: models.length, icon: <Layers3 />, tone: 'info' },
      { label: t('models.statusActive'), value: models.filter(model => model.status === 'AKTIF').length, icon: <Boxes />, tone: 'success' },
      { label: t('models.objectCount'), value: models.reduce((sum, model) => sum + (model.dataObjectCount ?? 0), 0), icon: <Database />, tone: 'neutral' },
      { label: tr ? 'Reverse Hazır' : 'Reverse Ready', value: models.filter(model => model.reverseEnvironmentUuid).length, icon: <ScanSearch />, tone: 'teal' },
    ]} />
    <section className="connections-records">
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('models.empty')} description={t('models.emptyHint')} action={addButton} /> :
      <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid auditKind="models" auditInFooter collectionTitle={tr ? 'Model Kataloğu' : 'Model Catalog'} collectionIcon={<Layers3 />} toolbarActions={addButton} cardHeaderLeadingField="provider" cardHeaderField="status" cardHiddenFields={['provider', 'status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="provider">{tr ? 'Teknoloji' : 'Technology'}</th><th data-field-key="name">{t('models.name')}</th><th data-field-key="description">{t('models.descriptionField')}</th><th data-field-key="schema">{t('models.logicalSchema')}</th><th data-field-key="environment">{tr ? 'Reverse Ortamı' : 'Reverse Context'}</th><th data-field-key="mode">{tr ? 'Reverse Modu' : 'Reverse Mode'}</th><th data-field-key="objects">{t('models.objectCount')}</th><th data-field-key="updated">{t('models.lastMetadataUpdate')}</th><th data-field-key="status">{t('models.status')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{visible.map(model => { const active = model.status === 'AKTIF'; const tone = active ? 'success' : 'neutral'; const technology = schemaOf(model)?.databaseType ?? model.technologyCode ?? ''; return <tr key={model.uuid} data-connection-uuid={model.uuid}>
        <td><span className="provider-cell"><DatabaseProviderIcon databaseType={technology} /><span>{databaseProviderVisual(technology).label}</span></span></td>
        <td><span className="connection-record-identity"><strong>{model.name}</strong><small>{model.code}</small></span></td>
        <td>{model.description || t('common.noDescription')}</td>
        <td>{schemaOf(model)?.name ?? ''}</td>
        <td>{environments.find(environment => environment.uuid === model.reverseEnvironmentUuid)?.name ?? (tr ? 'Seçilmedi' : 'Not selected')}</td>
        <td>{model.reverseMode === 'CUSTOM_RKM' ? 'RKM' : tr ? 'Standart' : 'Standard'}</td>
        <td>{model.dataObjectCount ?? 0}</td><td>{formatDate(model.lastMetadataUpdate)}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={active ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{active ? t('models.statusActive') : t('models.statusInactive')}</span></Tag></td>
        <td className="row-actions"><div className="connection-row-actions"><Tooltip title="Reverse Engineer"><Button aria-label={`Reverse Engineer: ${model.name}`} icon={<ScanSearch size={15} />} onClick={() => navigate(`${modelPath(model)}/import`)} /></Tooltip><RecordActionButton name={model.name} editable={canManage} onClick={() => showEdit(model)} /></div></td>
      </tr> })}</tbody></DataGrid>}</ProgressiveRecords>}
    </section>
    <RecordDetailDialog open={open} title={editing ? <span className="connection-dialog-title"><Layers3 size={17} aria-hidden="true" />{editing.name}<small className="sr-only">{canManage ? (tr ? 'Modeli Düzenle' : 'Edit Model') : (tr ? 'Modeli Görüntüle' : 'View Model')}</small></span> : t('models.create')} busy={busy} readOnly={!canManage} onClose={closeEditor} className="connection-catalog-dialog">
      {editing ? <p className="connection-panel-description"><FileText size={16} aria-hidden="true" />{editing.description || t('common.noDescription')}</p> : null}
      {editing ? <section className="connection-card-audit"><h3>{tr ? 'Kayıt Bilgileri' : 'Record Information'}</h3><RecordAuditFields record={audit.records[editing.uuid]} state={audit.state} /></section> : null}
      <form className="model-editor-form" onSubmit={(event) => void save(event)}><fieldset disabled={!canManage}><Tabs items={[
        { key: 'definition', label: tr ? 'Tanım' : 'Definition', children: <div className="model-form-grid">
          <label>{t('models.name')}<AntInput value={form.name} onChange={event => set('name', event.target.value)} required /></label>
          <label>{t('models.code')}<AntInput value={form.code} disabled={Boolean(editing)} onChange={event => set('code', event.target.value.toUpperCase())} pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required /></label>
          <label>{tr ? 'Teknoloji' : 'Technology'}<span className="provider-cell"><DatabaseProviderIcon databaseType={form.technologyCode} /><span>{databaseProviderVisual(form.technologyCode).label}</span></span></label>
          <label>{t('models.logicalSchema')}<FormSelect value={form.logicalSchemaUuid} onChange={event => { const schema = logicalSchemas.find(item => item.uuid === event.target.value); setForm(current => ({ ...current, logicalSchemaUuid: event.target.value, technologyCode: schema?.databaseType || current.technologyCode })) }} required>{logicalSchemas.map(schema => <option key={schema.uuid} value={schema.uuid}>{schema.name} ({schema.code})</option>)}</FormSelect></label>
          <label className="model-form-wide">{t('models.descriptionField')}<AntInput.TextArea value={form.description} onChange={event => set('description', event.target.value)} rows={3} /></label>
        </div> },
        { key: 'reverse', label: 'Reverse Engineering', children: <div className="model-form-grid">
          <label>{t('models.environment')}<FormSelect value={form.reverseEnvironmentUuid} onChange={event => set('reverseEnvironmentUuid', event.target.value)}><option value="">{tr ? 'Çalıştırırken seç' : 'Choose when running'}</option>{environments.map(environment => <option key={environment.uuid} value={environment.uuid}>{environment.name}</option>)}</FormSelect></label>
          <label>{tr ? 'Keşif Modu' : 'Discovery Mode'}<FormSelect value={form.reverseMode} onChange={event => set('reverseMode', event.target.value as FormState['reverseMode'])}><option value="STANDARD">{tr ? 'Standart JDBC' : 'Standard JDBC'}</option><option value="CUSTOM_RKM">{tr ? 'Özel RKM' : 'Custom RKM'}</option></FormSelect></label>
          {form.reverseMode === 'CUSTOM_RKM' ? <label className="model-form-wide">RKM<FormSelect value={form.rkmDefinitionUuid} onChange={event => set('rkmDefinitionUuid', event.target.value)} required><option value="">{tr ? 'RKM seçin' : 'Select RKM'}</option>{rkms.map(rkm => <option key={rkm.uuid} value={rkm.uuid}>{rkm.name} ({rkm.code})</option>)}</FormSelect></label> : null}
          <div className="model-reverse-note"><ScanSearch size={18} /><p>{tr ? 'Model seviyesinde toplu keşif, datastore seviyesinde ise tek nesne metadata yenilemesi yapılır. Bağlantı seçilen ortam ve mantıksal şema üzerinden çözülür.' : 'Run bulk discovery at model level and refresh a single object at datastore level. The connection resolves through the environment and logical schema.'}</p></div>
        </div> },
      ]} /></fieldset><footer><Button type="button" onClick={closeEditor}>{canManage ? t('common.cancel') : t('common.close')}</Button>{canManage ? <>{editing ? <Popconfirm title={tr ? 'Model silinsin mi?' : 'Delete model?'} description={tr ? 'Bu işlem geri alınamaz.' : 'This action cannot be undone.'} okText={tr ? 'Sil' : 'Delete'} cancelText={tr ? 'İptal' : 'Cancel'} okButtonProps={{ danger: true }} onConfirm={() => void removeModel()}><Button type="button" tone="danger" icon={<Trash2 size={16} />}>{tr ? 'Sil' : 'Delete'}</Button></Popconfirm> : null}<Button type="submit" tone="primary" busy={busy}>{tr ? 'Kaydet' : 'Save'}</Button></> : null}</footer></form>
    </RecordDetailDialog>
  </section>
}
