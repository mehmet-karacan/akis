import { Input, Switch } from 'antd'
import { Database, Save, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Dialog, RecordDetailDialog } from '../../core/ui'
import { SuggestionInput } from '../../core/ui/SuggestionInput'
import { RecordAuditFields } from '../../core/ui/RecordAuditFields'
import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { topologyApi, type PhysicalSchema, type PhysicalSchemaRequest } from '../topology/api'

interface Draft {
  name: string
  description: string
  catalogName: string
  schemaName: string
  workCatalogName: string
  workSchemaName: string
  defaultSchema: boolean
  loadingPrefix: string
  integrationPrefix: string
  errorPrefix: string
  tempPrefix: string
}

const emptyDraft: Draft = {
  name: '', description: '', catalogName: '', schemaName: '', workCatalogName: '', workSchemaName: '', defaultSchema: false,
  loadingPrefix: 'C$_', integrationPrefix: 'I$_', errorPrefix: 'E$_', tempPrefix: 'T$_',
}

const draftFrom = (item: PhysicalSchema): Draft => ({
  name: item.name, description: item.description ?? '', catalogName: item.catalogName ?? '', schemaName: item.schemaName,
  workCatalogName: item.workCatalogName ?? '', workSchemaName: item.workSchemaName ?? '', defaultSchema: item.defaultSchema,
  loadingPrefix: item.loadingPrefix, integrationPrefix: item.integrationPrefix, errorPrefix: item.errorPrefix, tempPrefix: item.tempPrefix,
})

const PREFIX = /^[A-Z][A-Z0-9_$]{0,7}$/

/** Creates a physical schema when `item` is absent, otherwise edits it. */
export function PhysicalSchemaDetailDialog({ projectUuid, connectionUuid, item, canManage, dependencyCount = 0, suggestions = [], onLoadSuggestions, onClose, onChanged }: {
  projectUuid: string
  connectionUuid: string
  item?: PhysicalSchema
  canManage: boolean
  dependencyCount?: number
  suggestions?: string[]
  onLoadSuggestions?(): Promise<void>
  onClose(): void
  onChanged(deleted: boolean): void | Promise<void>
}) {
  const blocked = dependencyCount > 0
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const audit = useRecordAudit('physical-schemas', item?.uuid ?? '')
  const [draft, setDraft] = useState<Draft>(() => item ? draftFrom(item) : emptyDraft)
  const [busy, setBusy] = useState(false)
  const [discovering, setDiscovering] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState('')
  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }))
  const prefixes = [draft.loadingPrefix, draft.integrationPrefix, draft.errorPrefix, draft.tempPrefix]
  const prefixesValid = prefixes.every((value) => PREFIX.test(value)) && new Set(prefixes).size === 4

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!canManage) return
    if (!draft.schemaName.trim() || !prefixesValid) { setError(tr ? 'Şema adı ve prefixler geçerli olmalıdır.' : 'Schema name and prefixes must be valid.'); return }
    setBusy(true); setError('')
    const request: PhysicalSchemaRequest = {
      connectionUuid, name: draft.name.trim() || undefined, description: draft.description.trim() || null,
      catalogName: draft.catalogName.trim() || null, schemaName: draft.schemaName.trim().toUpperCase(),
      workCatalogName: draft.workCatalogName.trim() || null, workSchemaName: draft.workSchemaName.trim().toUpperCase() || null,
      defaultSchema: draft.defaultSchema,
      loadingPrefix: draft.loadingPrefix, integrationPrefix: draft.integrationPrefix, errorPrefix: draft.errorPrefix, tempPrefix: draft.tempPrefix,
    }
    try {
      if (item) await topologyApi.updatePhysicalSchema(projectUuid, item.uuid, request)
      else await topologyApi.createPhysicalSchema(projectUuid, request)
      await onChanged(false)
      onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  async function remove() {
    if (!item || blocked) return
    setBusy(true); setError('')
    try {
      await topologyApi.deletePhysicalSchema(projectUuid, item.uuid)
      setConfirmDelete(false)
      await onChanged(true)
      onClose()
    } catch (reason) { setConfirmDelete(false); setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  async function discover() {
    if (!onLoadSuggestions) return
    setDiscovering(true); setError('')
    try { await onLoadSuggestions() }
    catch (reason) { setError(reason instanceof Error ? reason.message : t('schemas.schemaDiscoveryFailed')) }
    finally { setDiscovering(false) }
  }

  const prefixFields: Array<[keyof Draft, string]> = [
    ['loadingPrefix', tr ? 'Yükleme (LKM)' : 'Loading (LKM)'],
    ['integrationPrefix', tr ? 'Entegrasyon (IKM)' : 'Integration (IKM)'],
    ['errorPrefix', tr ? 'Hata (CKM)' : 'Error (CKM)'],
    ['tempPrefix', tr ? 'Geçici' : 'Temporary'],
  ]

  return <>
    <RecordDetailDialog open title={item ? item.name : t('schemas.addPhysical')} busy={busy} readOnly={!canManage} onClose={onClose}>
      {error && <div className="error-banner" role="alert">{error}</div>}
      <form onSubmit={(event) => void save(event)}>
        <section className="connection-detail-section">
          <h2>{tr ? 'Fiziksel Şema Tanımı' : 'Physical Schema Definition'}</h2>
          <div className="form-grid two-column">
            <label>{tr ? 'Şema Adı' : 'Schema Name'} *<span className="physical-schema-suggest"><SuggestionInput suggestions={suggestions} value={draft.schemaName} onChange={(event) => update('schemaName', event.target.value.toUpperCase())} disabled={!canManage} required placeholder="INNOVA_ODI" />{onLoadSuggestions && canManage && <Button type="button" icon={<Database size={14} />} busy={discovering} onClick={() => void discover()}>{t('schemas.loadUsers')}</Button>}</span></label>
            <label>{t('schemas.name')}<Input value={draft.name} onChange={(event) => update('name', event.target.value)} disabled={!canManage} placeholder={draft.schemaName} /></label>
            <label>{tr ? 'Çalışma Şeması' : 'Work Schema'}<Input value={draft.workSchemaName} onChange={(event) => update('workSchemaName', event.target.value.toUpperCase())} disabled={!canManage} placeholder={draft.schemaName} /></label>
            <label>{tr ? 'Katalog' : 'Catalog'}<Input value={draft.catalogName} onChange={(event) => update('catalogName', event.target.value)} disabled={!canManage} /></label>
            <label>{tr ? 'Çalışma Kataloğu' : 'Work Catalog'}<Input value={draft.workCatalogName} onChange={(event) => update('workCatalogName', event.target.value)} disabled={!canManage} /></label>
            <label>{tr ? 'Varsayılan Şema' : 'Default Schema'}<span><Switch checked={draft.defaultSchema} onChange={(value) => update('defaultSchema', value)} disabled={!canManage} /></span></label>
            <label className="form-grid-wide">{t('common.description')}<Input.TextArea rows={2} value={draft.description} onChange={(event) => update('description', event.target.value)} disabled={!canManage} /></label>
          </div>
        </section>
        <section className="connection-detail-section">
          <h2>{tr ? 'Çalışma Tablosu Prefixleri' : 'Work Table Prefixes'}</h2>
          <div className="form-grid two-column">
            {prefixFields.map(([key, label]) => <label key={key}>{label}<Input maxLength={8} value={String(draft[key])} onChange={(event) => update(key, event.target.value.toUpperCase() as Draft[typeof key])} disabled={!canManage} status={PREFIX.test(String(draft[key])) ? undefined : 'error'} /></label>)}
          </div>
          <small>{tr ? '1-8 karakter; A-Z ile başlayıp A-Z, 0-9, _ veya $ içerebilir. Prefixler birbirinden farklı olmalıdır.' : '1-8 characters; start with A-Z, followed by A-Z, 0-9, _ or $. Prefixes must differ.'}</small>
        </section>
        {item && <section className="connection-card-audit"><h3>{tr ? 'Kayıt Bilgileri' : 'Record Information'}</h3><RecordAuditFields record={audit.records[item.uuid]} state={audit.state} /></section>}
        <footer>
          {item && canManage && <Button type="button" tone="danger" icon={<Trash2 size={16} />} onClick={() => setConfirmDelete(true)}>{t('common.delete')}</Button>}
          <Button type="button" onClick={onClose}>{t('common.cancel')}</Button>
          {canManage && <Button type="submit" tone="primary" icon={<Save size={16} />} busy={busy}>{t('common.save')}</Button>}
        </footer>
      </form>
    </RecordDetailDialog>
    {item && <Dialog open={confirmDelete} title={tr ? 'Fiziksel Şemayı Sil' : 'Delete Physical Schema'} closeLabel={t('common.close')} busy={busy} onClose={() => setConfirmDelete(false)}>
      {dependencyCount > 0 && <p role="alert">{t('schemas.deletePhysicalBlocked', { count: dependencyCount })}</p>}
      {!blocked && <p>{tr ? `${item.name} fiziksel şeması silinecek. Bu işlem geri alınamaz.` : `${item.name} will be deleted. This action cannot be undone.`}</p>}
      <footer><Button onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button><Button tone="danger" icon={<Trash2 size={16} />} busy={busy} disabled={blocked} onClick={() => void remove()}>{t('common.delete')}</Button></footer>
    </Dialog>}
  </>
}
