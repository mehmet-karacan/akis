import { Input, Tabs } from 'antd'
import { Database, FileText, GitBranch, Link2, Save, ShieldAlert, Trash2, Unlink, Workflow } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { RecordAuditFields } from '../../core/ui/RecordAuditFields'
import { Select as FormSelect } from '../../core/ui/Select'
import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { topologyApi, type Connection, type PhysicalSchema } from '../topology/api'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { compatiblePhysicalSchemas, type LogicalSchemaCatalogItem } from './logicalCatalog'
import '../connections/connections.css'

interface Props {
  item: LogicalSchemaCatalogItem
  physicalSchemas: PhysicalSchema[]
  connections: Connection[]
  onClose(): void
  onChanged(deleted: boolean): Promise<void> | void
}

/** Logical schema record: definition tab plus one physical mapping per environment. */
export function LogicalSchemaDetailDialog({ item, physicalSchemas, connections, onClose, onChanged }: Props) {
  const { schema, mappings, mappedCount } = item
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const projectUuid = useCurrentProjectUuid()
  const { can } = useProjectAccess()
  const canWrite = can('BAGLANTI_YONET')
  const audit = useRecordAudit('logical-schemas', schema.uuid)
  const [name, setName] = useState(schema.name)
  const [description, setDescription] = useState(schema.description ?? '')
  const [status, setStatus] = useState(schema.status)
  const [selection, setSelection] = useState<Record<string, string>>(() => Object.fromEntries(mappings.map((m) => [m.environment.uuid, m.binding?.physicalSchemaUuid ?? ''])))
  const [busy, setBusy] = useState<string>('')
  const [error, setError] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const compatible = compatiblePhysicalSchemas(schema.databaseType, physicalSchemas)
  const physicalLabel = (physical: PhysicalSchema) => `${connections.find((c) => c.uuid === physical.connectionUuid)?.code ?? '?'} / ${physical.schemaName}`

  async function saveDefinition(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || !name.trim()) return
    setBusy('definition'); setError('')
    try {
      await topologyApi.updateContext(projectUuid, 'logical-schemas', schema.uuid, { name: name.trim(), description: description.trim() || null, status })
      notifyFeedback(tr ? 'Mantıksal şema kaydedildi.' : 'Logical schema saved.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function saveMapping(environmentUuid: string) {
    const mapping = mappings.find((m) => m.environment.uuid === environmentUuid)
    const physicalSchemaUuid = selection[environmentUuid]
    if (!mapping || !physicalSchemaUuid) return
    setBusy(environmentUuid); setError('')
    try {
      const body = { logicalSchemaUuid: schema.uuid, environmentUuid, physicalSchemaUuid }
      if (mapping.binding) await topologyApi.updateBinding(projectUuid, mapping.binding.uuid, body)
      else await topologyApi.createBinding(projectUuid, body)
      notifyFeedback(tr ? 'Eşleme kaydedildi.' : 'Mapping saved.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function removeMapping(environmentUuid: string) {
    const mapping = mappings.find((m) => m.environment.uuid === environmentUuid)
    if (!mapping?.binding) return
    setBusy(environmentUuid); setError('')
    try {
      await topologyApi.deleteBinding(projectUuid, mapping.binding.uuid)
      setSelection((current) => ({ ...current, [environmentUuid]: '' }))
      notifyFeedback(tr ? 'Eşleme kaldırıldı.' : 'Mapping removed.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function remove() {
    setBusy('delete'); setError('')
    try {
      await topologyApi.deleteContext(projectUuid, 'logical-schemas', schema.uuid)
      setConfirmDelete(false)
      notifyFeedback(tr ? 'Mantıksal şema silindi.' : 'Logical schema deleted.')
      await onChanged(true)
      onClose()
    } catch (reason) { setConfirmDelete(false); setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  const definitionTab = <section className="connection-detail-section">
    <form className="topology-form" onSubmit={(event) => void saveDefinition(event)}>
      <fieldset disabled={!canWrite || Boolean(busy)} className="connection-editor-fields">
        <div className="form-grid two-column">
          <label>{t('connections.provider')}<span className="provider-cell"><DatabaseProviderIcon databaseType={schema.databaseType ?? ''} /><span>{databaseProviderVisual(schema.databaseType ?? '').label}</span></span></label>
          <label>{t('schemas.code')}<Input value={schema.code} readOnly /></label>
          <label>{t('schemas.name')} *<Input required value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label>{t('connections.status')}<FormSelect value={status} onChange={(e) => setStatus(e.target.value)}><option value="ETKIN">{tr ? 'Etkin' : 'Active'}</option><option value="PASIF">{tr ? 'Pasif' : 'Inactive'}</option></FormSelect></label>
          <label className="form-grid-wide">{t('schemas.description')}<Input.TextArea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        </div>
      </fieldset>
      <section className="connection-card-audit"><h3>{tr ? 'Kayıt Bilgileri' : 'Record Information'}</h3><RecordAuditFields record={audit.records[schema.uuid]} state={audit.state} /></section>
      {canWrite && <footer className="topology-form-actions topology-form-actions--right">
        <Button type="button" tone="danger" icon={<Trash2 size={16} />} disabled={Boolean(busy)} onClick={() => setConfirmDelete(true)}>{t('common.delete')}</Button>
        <Button type="submit" tone="primary" icon={<Save size={16} />} busy={busy === 'definition'}>{t('common.save')}</Button>
      </footer>}
    </form>
  </section>

  const mappingsTab = <section className="connection-detail-section">
    <p>{tr ? 'Her ortam için bu mantıksal şemanın çözümleneceği fiziksel şemayı seçin. Yalnız aynı sağlayıcıdaki fiziksel şemalar listelenir.' : 'Pick the physical schema this logical schema resolves to in each environment. Only physical schemas of the same provider are listed.'}</p>
    {mappings.length === 0 ? <p className="form-note">{t('schemas.bindingPrerequisiteHint')}</p> : <div className="physical-schema-table"><DataGrid viewControls={false}>
      <thead><tr><th>{t('schemas.environment')}</th><th>{t('connections.connection')}</th><th>{t('schemas.physicalSchema')}</th><th className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{mappings.map((mapping) => {
        const envUuid = mapping.environment.uuid
        const selected = selection[envUuid] ?? ''
        const dirty = selected !== (mapping.binding?.physicalSchemaUuid ?? '')
        return <tr key={envUuid}>
          <td><span className="connection-record-identity"><strong>{mapping.environment.name}</strong><small>{mapping.environment.code}</small></span></td>
          <td>{mapping.connection ? <span className="provider-cell"><DatabaseProviderIcon databaseType={mapping.connection.databaseType} /><span>{mapping.connection.name}</span></span> : <span className="form-note">{t('schemas.notMapped')}</span>}</td>
          <td><FormSelect value={selected} disabled={!canWrite || Boolean(busy)} onChange={(e) => setSelection((current) => ({ ...current, [envUuid]: e.target.value }))} aria-label={`${t('schemas.physicalSchema')}: ${mapping.environment.name}`}>
            <option value="">{t('schemas.choosePhysicalSchema')}</option>
            {compatible.map((physical) => <option key={physical.uuid} value={physical.uuid}>{physicalLabel(physical)}</option>)}
          </FormSelect></td>
          <td className="ui-grid-actions-column"><div className="connection-row-actions">
            {canWrite && <Button type="button" tone="primary" icon={<Link2 size={14} />} disabled={!selected || !dirty} busy={busy === envUuid} onClick={() => void saveMapping(envUuid)}>{t('schemas.saveBinding')}</Button>}
            {canWrite && mapping.binding && <Button type="button" tone="danger" icon={<Unlink size={14} />} disabled={Boolean(busy)} onClick={() => void removeMapping(envUuid)}>{tr ? 'Kaldır' : 'Remove'}</Button>}
          </div></td>
        </tr>
      })}</tbody>
    </DataGrid></div>}
  </section>

  return <>
    <RecordDetailDialog open title={<span className="connection-dialog-title"><GitBranch size={17} aria-hidden="true" />{schema.name}</span>} busy={Boolean(busy)} readOnly={!canWrite} onClose={onClose} className="connection-catalog-dialog">
      <section className="page-stack connection-detail-page">
        <p className="connection-panel-description"><FileText size={16} aria-hidden="true" />{schema.description || t('common.noDescription')}</p>
        {error ? <div className="error-banner" role="alert">{error}</div> : null}
        <SummaryStrip ariaLabel={t('schemas.logicalTitle')} items={[
          { label: tr ? 'Eşlenmiş Ortam' : 'Mapped Environments', value: mappedCount, icon: <Workflow />, tone: mappedCount === mappings.length && mappings.length > 0 ? 'success' : 'warning' },
          { label: tr ? 'Toplam Ortam' : 'Environments', value: mappings.length, icon: <Database />, tone: 'neutral' },
        ]} />
        <Tabs items={[
          { key: 'definition', label: <span className="connection-tab-label connection-tab-definition"><GitBranch size={16} />{tr ? 'Şema Tanımı' : 'Schema Definition'}</span>, children: definitionTab },
          { key: 'mappings', label: <span className="connection-tab-label connection-tab-physical"><Link2 size={16} />{tr ? 'Ortam Eşlemeleri' : 'Environment Mappings'}</span>, children: mappingsTab },
        ]} />
      </section>
    </RecordDetailDialog>
    <Dialog open={confirmDelete} title={tr ? 'Mantıksal Şemayı Sil' : 'Delete Logical Schema'} closeLabel={t('common.close')} busy={busy === 'delete'} onClose={() => setConfirmDelete(false)}>
      <div className="connection-delete-impact"><ShieldAlert />
        <p>{mappedCount > 0 ? (tr ? `${schema.name} ${mappedCount} ortam eşlemesinde kullanılıyor; önce eşlemeleri kaldırın.` : `${schema.name} is used by ${mappedCount} environment mapping(s); remove them first.`) : (tr ? `${schema.name} silinecek. Bu işlem geri alınamaz.` : `${schema.name} will be deleted. This action cannot be undone.`)}</p>
        <footer><Button onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button><Button tone="danger" icon={<Trash2 size={16} />} busy={busy === 'delete'} disabled={mappedCount > 0} onClick={() => void remove()}>{t('common.delete')}</Button></footer>
      </div>
    </Dialog>
  </>
}
