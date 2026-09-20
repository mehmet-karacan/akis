import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { FolderPlus } from 'lucide-react'
import { Button, Dialog } from '../../core/ui'
import { topologyApi, type Submodel } from '../topology/api'

export function CreateModelFolder({ projectUuid, modelUuid, folders, parentUuid, onCreated }: { projectUuid: string; modelUuid: string; folders: Submodel[]; parentUuid?: string; onCreated(): void }) {
  const { i18n, t } = useTranslation()
  const tr = i18n.language === 'tr'
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const data = new FormData(event.currentTarget)
    setBusy(true); setError('')
    try {
      await topologyApi.createSubmodel(projectUuid, modelUuid, { parentUuid: data.get('parentUuid') || null, code: String(data.get('code')).trim().toUpperCase(), name: String(data.get('name')).trim() })
      setOpen(false); onCreated(); window.dispatchEvent(new Event('akis:models-changed'))
    } catch { setError(t('common.saveError')) } finally { setBusy(false) }
  }
  return <><Button icon={<FolderPlus size={16} />} onClick={() => setOpen(true)}>{tr ? 'Klasör Ekle' : 'Add Folder'}</Button><Dialog open={open} title={tr ? 'Model Klasörü Ekle' : 'Add Model Folder'} closeLabel={t('common.close')} onClose={() => setOpen(false)} busy={busy}><form onSubmit={(event) => void save(event)}>{error && <p role="alert">{error}</p>}<label>{tr ? 'Üst Klasör' : 'Parent Folder'}<FormSelect name="parentUuid" defaultValue={parentUuid ?? ''}><option value="">{tr ? 'Model Kökü' : 'Model Root'}</option>{folders.map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} ({folder.code})</option>)}</FormSelect></label><label>{tr ? 'Kod' : 'Code'}<AntInput name="code" required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" /></label><label>{tr ? 'Ad' : 'Name'}<AntInput name="name" required /></label><footer><Button type="submit" tone="primary" busy={busy}>{tr ? 'Kaydet' : 'Save'}</Button></footer></form></Dialog></>
}
