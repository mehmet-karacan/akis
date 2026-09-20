import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FolderInput } from 'lucide-react'
import { Select } from 'antd'
import { Button } from '../../core/ui'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { topologyApi, type DataObject, type Submodel } from '../topology/api'

function folderPath(folder: Submodel, folders: Submodel[]): string {
  const names = [folder.name]
  const seen = new Set([folder.uuid])
  let parent = folder.parentUuid
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    const item = folders.find(candidate => candidate.uuid === parent)
    if (!item) break
    names.unshift(item.name); parent = item.parentUuid
  }
  return names.join(' / ')
}

export function DataObjectFolderEditor({ projectUuid, object, folders, onMoved }: {
  projectUuid: string; object: DataObject; folders: Submodel[]; onMoved(object: DataObject): void
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const [folderUuid, setFolderUuid] = useState(object.submodelUuid ?? '')
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  async function move() {
    if (pending.current || folderUuid === (object.submodelUuid ?? '')) return
    pending.current = true; setBusy(true)
    try {
      const next = await topologyApi.moveDataObject(projectUuid, object.modelUuid, object.uuid, {
        submodelUuid: folderUuid || null, expectedVersion: object.version,
      })
      window.dispatchEvent(new Event('akis:models-changed'))
      if (!active.current) return
      onMoved(next)
      notifyFeedback(tr ? 'Data Store klasörü güncellendi.' : 'Data store folder updated.', 'success')
    } catch (error) {
      if (active.current) notifyFeedback(error instanceof Error ? error.message : (tr ? 'Klasör güncellenemedi.' : 'Could not update the folder.'), 'error')
    } finally {
      pending.current = false
      if (active.current) setBusy(false)
    }
  }
  return <section className="data-object-folder-editor" aria-label={tr ? 'Data Store Klasörü' : 'Data Store Folder'}>
    <label><span>{tr ? 'Klasör' : 'Folder'}</span><Select aria-label={tr ? 'Data Store Klasörü' : 'Data Store Folder'}
      value={folderUuid} disabled={busy} onChange={setFolderUuid} showSearch optionFilterProp="label"
      options={[{ value: '', label: tr ? 'Model Kökü' : 'Model Root' }, ...folders.filter(item => item.modelUuid === object.modelUuid).map(item => ({ value: item.uuid, label: folderPath(item, folders) }))]} /></label>
    <Button icon={<FolderInput size={16} />} busy={busy} disabled={busy || folderUuid === (object.submodelUuid ?? '')} onClick={() => void move()}>{tr ? 'Klasörü Güncelle' : 'Update Folder'}</Button>
    <small>{tr ? 'Yalnız katalog yerleşimi değişir; kaynak tablo ve kolon eşlemeleri değişmez.' : 'Only the catalog location changes; source tables and column mappings stay unchanged.'}</small>
  </section>
}
