import { Input, Popconfirm } from 'antd'
import { Save, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { Button, Dialog } from '../../core/ui'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { topologyApi, type LogicalSchema, type Environment } from '../topology/api'
import { SchemaContextDetailPage } from './SchemaContextDetailPage'
import { useRecordAudit } from '../../core/ui/useRecordAudit'
import { RecordAuditFields } from '../../core/ui/RecordAuditFields'

export function ContextRecordDialog({ item, kind, onClose, onSaved }: {
  item: LogicalSchema | Environment; kind: 'logical' | 'environment'; onClose: () => void; onSaved: (deleted: boolean) => void
}) {
  const { t } = useTranslation()
  const projectUuid = useCurrentProjectUuid()
  const { can } = useProjectAccess()
  const editable = can('BAGLANTI_YONET')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const endpoint = kind === 'logical' ? 'logical-schemas' : 'environments'
  const audit = useRecordAudit(endpoint, String(item.version))
  async function persist(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault()
    if (!editable || busy) return
    const data = event ? new FormData(event.currentTarget) : null
    setBusy(true); setError('')
    try {
      if (data) await topologyApi.updateContext(projectUuid, endpoint, item.uuid, { name: String(data.get('name')).trim(), description: String(data.get('description') ?? '').trim(), expectedVersion: item.version })
      else await topologyApi.deleteContext(projectUuid, endpoint, item.uuid, item.version)
      onSaved(!data); onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }
  return <Dialog open title={item.name} closeLabel={t('common.close')} busy={busy} onClose={onClose}>
    <FeedbackToast message={error} tone="error" onClose={() => setError('')} />
    <RecordAuditFields record={audit.records[item.uuid]} state={audit.state} />
    <form onSubmit={(event) => void persist(event)}>
      <fieldset disabled={!editable || busy} className="context-record-fields">
        <label>{t('schemas.name')}<Input name="name" defaultValue={item.name} required /></label>
        <label>{t('schemas.code')}<Input value={item.code} readOnly /></label>
        {kind === 'logical' && <label className="context-record-description">{t('schemas.description')}<Input.TextArea name="description" rows={2} defaultValue={'description' in item ? item.description ?? '' : ''} /></label>}
      </fieldset>
      <SchemaContextDetailPage kind={kind} recordUuid={item.uuid} embedded />
      <footer>
        <Button onClick={onClose}>{t('common.close')}</Button>
        {editable && <><Popconfirm title={t('schemas.deleteConfirm')} description={t('schemas.deleteHint')} onConfirm={() => persist()} okText={t('common.delete')} cancelText={t('common.cancel')} okButtonProps={{ danger: true }}><Button tone="danger" disabled={busy} icon={<Trash2 size={16} />}>{t('common.delete')}</Button></Popconfirm><Button type="submit" tone="primary" busy={busy} icon={<Save size={16} />}>{t('common.save')}</Button></>}
      </footer>
    </form>
  </Dialog>
}
