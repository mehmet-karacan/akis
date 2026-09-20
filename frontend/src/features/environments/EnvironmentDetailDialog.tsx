import { Input, Switch, Tabs } from 'antd'
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
import { topologyApi, type Connection, type EnvironmentRisk, type PhysicalSchema } from '../topology/api'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { compatiblePhysicalSchemas } from '../schemas/logicalCatalog'
import { ENVIRONMENT_RISKS, riskLabel, type EnvironmentCatalogItem } from './environmentCatalog'
import '../connections/connections.css'

interface Props {
  item: EnvironmentCatalogItem
  physicalSchemas: PhysicalSchema[]
  connections: Connection[]
  onClose(): void
  onChanged(deleted: boolean): Promise<void> | void
}

/** Environment record: definition tab plus one physical mapping per logical schema (the ODI context matrix row). */
export function EnvironmentDetailDialog({ item, physicalSchemas, connections, onClose, onChanged }: Props) {
  const { environment, mappings, mappedCount } = item
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const projectUuid = useCurrentProjectUuid()
  const { can } = useProjectAccess()
  const canWrite = can('BAGLANTI_YONET')
  const audit = useRecordAudit('environments', environment.uuid)
  const [name, setName] = useState(environment.name)
  const [description, setDescription] = useState(environment.description ?? '')
  const [risk, setRisk] = useState<EnvironmentRisk>((environment.risk as EnvironmentRisk) ?? 'DUSUK')
  const [defaultEnvironment, setDefaultEnvironment] = useState(environment.defaultEnvironment)
  const [status, setStatus] = useState(environment.status)
  const [selection, setSelection] = useState<Record<string, string>>(() => Object.fromEntries(mappings.map((m) => [m.logicalSchema.uuid, m.binding?.physicalSchemaUuid ?? ''])))
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const physicalLabel = (physical: PhysicalSchema) => `${connections.find((c) => c.uuid === physical.connectionUuid)?.code ?? '?'} / ${physical.schemaName}`

  async function saveDefinition(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || !name.trim()) return
    setBusy('definition'); setError('')
    try {
      await topologyApi.updateEnvironment(projectUuid, environment.uuid, { name: name.trim(), description: description.trim() || null, risk, defaultEnvironment, status })
      notifyFeedback(tr ? 'Ortam kaydedildi.' : 'Environment saved.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function saveMapping(logicalSchemaUuid: string) {
    const mapping = mappings.find((m) => m.logicalSchema.uuid === logicalSchemaUuid)
    const physicalSchemaUuid = selection[logicalSchemaUuid]
    if (!mapping || !physicalSchemaUuid) return
    setBusy(logicalSchemaUuid); setError('')
    try {
      const body = { logicalSchemaUuid, environmentUuid: environment.uuid, physicalSchemaUuid }
      if (mapping.binding) await topologyApi.updateBinding(projectUuid, mapping.binding.uuid, body)
      else await topologyApi.createBinding(projectUuid, body)
      notifyFeedback(tr ? 'Eşleme kaydedildi.' : 'Mapping saved.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function removeMapping(logicalSchemaUuid: string) {
    const mapping = mappings.find((m) => m.logicalSchema.uuid === logicalSchemaUuid)
    if (!mapping?.binding) return
    setBusy(logicalSchemaUuid); setError('')
    try {
      await topologyApi.deleteBinding(projectUuid, mapping.binding.uuid)
      setSelection((current) => ({ ...current, [logicalSchemaUuid]: '' }))
      notifyFeedback(tr ? 'Eşleme kaldırıldı.' : 'Mapping removed.')
      await onChanged(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  async function remove() {
    setBusy('delete'); setError('')
    try {
      await topologyApi.deleteEnvironment(projectUuid, environment.uuid)
      setConfirmDelete(false)
      notifyFeedback(tr ? 'Ortam silindi.' : 'Environment deleted.')
      await onChanged(true)
      onClose()
    } catch (reason) { setConfirmDelete(false); setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy('') }
  }

  const definitionTab = <section className="connection-detail-section">
    <form className="topology-form" onSubmit={(event) => void saveDefinition(event)}>
      <fieldset disabled={!canWrite || Boolean(busy)} className="connection-editor-fields">
        <div className="form-grid two-column">
          <label>{t('schemas.name')} *<Input required value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label>{t('schemas.code')}<Input value={environment.code} readOnly /></label>
          <label>{tr ? 'Risk Sınıfı' : 'Risk Class'}<FormSelect value={risk} onChange={(e) => setRisk(e.target.value as EnvironmentRisk)}>{ENVIRONMENT_RISKS.map((r) => <option key={r} value={r}>{riskLabel(r, tr)}</option>)}</FormSelect></label>
          <label>{t('connections.status')}<FormSelect value={status} onChange={(e) => setStatus(e.target.value)}><option value="ETKIN">{tr ? 'Etkin' : 'Active'}</option><option value="PASIF">{tr ? 'Pasif' : 'Inactive'}</option></FormSelect></label>
          <label>{tr ? 'Varsayılan Ortam' : 'Default Environment'}<span><Switch checked={defaultEnvironment} onChange={setDefaultEnvironment} disabled={!canWrite} /></span></label>
          <label className="form-grid-wide">{t('schemas.description')}<Input.TextArea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        </div>
        <p className="form-note">{t('schemas.environmentSimpleHint')}</p>
      </fieldset>
      <section className="connection-card-audit"><h3>{tr ? 'Kayıt Bilgileri' : 'Record Information'}</h3><RecordAuditFields record={audit.records[environment.uuid]} state={audit.state} /></section>
      {canWrite && <footer className="topology-form-actions topology-form-actions--right">
        <Button type="button" tone="danger" icon={<Trash2 size={16} />} disabled={Boolean(busy)} onClick={() => setConfirmDelete(true)}>{t('common.delete')}</Button>
        <Button type="submit" tone="primary" icon={<Save size={16} />} busy={busy === 'definition'}>{t('common.save')}</Button>
      </footer>}
    </form>
  </section>

  const mappingsTab = <section className="connection-detail-section">
    <p>{tr ? 'Her mantıksal şema için bu ortamda çözümlenecek fiziksel şemayı seçin. Yalnız aynı sağlayıcıdaki fiziksel şemalar listelenir.' : 'Pick the physical schema each logical schema resolves to in this environment. Only physical schemas of the same provider are listed.'}</p>
    {mappings.length === 0 ? <p className="form-note">{t('schemas.bindingPrerequisiteHint')}</p> : <div className="physical-schema-table"><DataGrid viewControls={false}>
      <thead><tr><th>{t('schemas.logicalSchema')}</th><th>{t('connections.connection')}</th><th>{t('schemas.physicalSchema')}</th><th className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
      <tbody>{mappings.map((mapping) => {
        const key = mapping.logicalSchema.uuid
        const selected = selection[key] ?? ''
        const dirty = selected !== (mapping.binding?.physicalSchemaUuid ?? '')
        const compatible = compatiblePhysicalSchemas(mapping.logicalSchema.databaseType, physicalSchemas)
        return <tr key={key}>
          <td><span className="provider-cell"><DatabaseProviderIcon databaseType={mapping.logicalSchema.databaseType ?? ''} /><span className="connection-record-identity"><strong>{mapping.logicalSchema.name}</strong><small>{mapping.logicalSchema.code} · {databaseProviderVisual(mapping.logicalSchema.databaseType ?? '').label}</small></span></span></td>
          <td>{mapping.connection ? mapping.connection.name : <span className="form-note">{t('schemas.notMapped')}</span>}</td>
          <td><FormSelect value={selected} disabled={!canWrite || Boolean(busy)} onChange={(e) => setSelection((current) => ({ ...current, [key]: e.target.value }))} aria-label={`${t('schemas.physicalSchema')}: ${mapping.logicalSchema.name}`}>
            <option value="">{t('schemas.choosePhysicalSchema')}</option>
            {compatible.map((physical) => <option key={physical.uuid} value={physical.uuid}>{physicalLabel(physical)}</option>)}
          </FormSelect></td>
          <td className="ui-grid-actions-column"><div className="connection-row-actions">
            {canWrite && <Button type="button" tone="primary" icon={<Link2 size={14} />} disabled={!selected || !dirty} busy={busy === key} onClick={() => void saveMapping(key)}>{t('schemas.saveBinding')}</Button>}
            {canWrite && mapping.binding && <Button type="button" tone="danger" icon={<Unlink size={14} />} disabled={Boolean(busy)} onClick={() => void removeMapping(key)}>{tr ? 'Kaldır' : 'Remove'}</Button>}
          </div></td>
        </tr>
      })}</tbody>
    </DataGrid></div>}
  </section>

  return <>
    <RecordDetailDialog open title={<span className="connection-dialog-title"><Workflow size={17} aria-hidden="true" />{environment.name}</span>} busy={Boolean(busy)} readOnly={!canWrite} onClose={onClose} className="connection-catalog-dialog">
      <section className="page-stack connection-detail-page">
        <p className="connection-panel-description"><FileText size={16} aria-hidden="true" />{environment.description || t('common.noDescription')}</p>
        {error ? <div className="error-banner" role="alert">{error}</div> : null}
        <SummaryStrip ariaLabel={t('schemas.environmentsTitle')} items={[
          { label: tr ? 'Eşlenmiş Mantıksal Şema' : 'Mapped Logical Schemas', value: mappedCount, icon: <GitBranch />, tone: mappedCount === mappings.length && mappings.length > 0 ? 'success' : 'warning' },
          { label: tr ? 'Toplam Mantıksal Şema' : 'Logical Schemas', value: mappings.length, icon: <Database />, tone: 'neutral' },
        ]} />
        <Tabs items={[
          { key: 'definition', label: <span className="connection-tab-label connection-tab-definition"><Workflow size={16} />{tr ? 'Ortam Tanımı' : 'Environment Definition'}</span>, children: definitionTab },
          { key: 'mappings', label: <span className="connection-tab-label connection-tab-physical"><Link2 size={16} />{tr ? 'Şema Eşlemeleri' : 'Schema Mappings'}</span>, children: mappingsTab },
        ]} />
      </section>
    </RecordDetailDialog>
    <Dialog open={confirmDelete} title={tr ? 'Ortamı Sil' : 'Delete Environment'} closeLabel={t('common.close')} busy={busy === 'delete'} onClose={() => setConfirmDelete(false)}>
      <div className="connection-delete-impact"><ShieldAlert />
        <p>{mappedCount > 0 ? (tr ? `${environment.name} ${mappedCount} şema eşlemesinde kullanılıyor; önce eşlemeleri kaldırın.` : `${environment.name} is used by ${mappedCount} schema mapping(s); remove them first.`) : (tr ? `${environment.name} silinecek. Yayın veya doğrulama kaydı olan ortamlar silinemez.` : `${environment.name} will be deleted. Environments with publications or validations cannot be deleted.`)}</p>
        <footer><Button onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button><Button tone="danger" icon={<Trash2 size={16} />} busy={busy === 'delete'} disabled={mappedCount > 0} onClick={() => void remove()}>{t('common.delete')}</Button></footer>
      </div>
    </Dialog>
  </>
}
