import { Switch } from 'antd'
import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { apiRequest, jsonBody } from '../../core/api/client'
import { Button } from '../../core/ui'

interface Policy {
  enabled: boolean
  allowSameSchema: boolean
  maxObjects: number
  maxRowsPerRun: number
  maxBytesPerRun: number
  retentionHours: number
}
interface View { policy: Policy; version: number }
const limits = [
  ['maxObjects', 'Maximum work objects', 'En fazla çalışma nesnesi', 1000],
  ['retentionHours', 'Retention hours', 'Saklama süresi (saat)', 8760],
] as const

export function WorkAreaPolicyEditor({ projectUuid, schemaUuid, schemaName, canManage }: {
  projectUuid: string; schemaUuid: string; schemaName: string; canManage: boolean
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const endpoint = `/api/v1/projects/${encodeURIComponent(projectUuid)}/physical-schemas/${encodeURIComponent(schemaUuid)}/work-area-policy`
  const [view, setView] = useState<View | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    setLoading(true); setView(null); setError(''); setSaved(false)
    void apiRequest<View>(endpoint, { signal: controller.signal }).then(result => {
      if (active) setView(result)
    }).catch(reason => {
      if (active) setError(reason instanceof Error ? reason.message : 'Policy could not be loaded.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false; controller.abort() }
  }, [endpoint, revision])
  const valid = view != null && limits.every(([key, , , maximum]) => Number.isSafeInteger(view.policy[key]) && view.policy[key] >= 1 && view.policy[key] <= maximum)
  const disabled = !canManage || loading || saving || !view
  function update<K extends keyof Policy>(key: K, value: Policy[K]) {
    setSaved(false)
    setView(current => current ? { ...current, policy: { ...current.policy, [key]: value } } : current)
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!canManage || !view || !valid || saving) return
    setSaving(true); setSaved(false); setError('')
    try {
      const result = await apiRequest<View>(endpoint, { method: 'PUT', ...jsonBody({ expectedVersion: view.version, policy: view.policy, automaticResources: true }) })
      setView(result); setSaved(true)
    } catch (reason) { setError(reason instanceof Error ? reason.message : (tr ? 'Politika kaydedilemedi.' : 'Policy could not be saved.')) }
    finally { setSaving(false) }
  }
  return <section className="connection-detail-section" aria-label={tr ? 'Çalışma Alanı Politikası' : 'Work Area Policy'}>
    <h2>{tr ? 'Çalışma Alanı Politikası' : 'Work Area Policy'}</h2>
    <p>{tr ? 'Uygulama kaynak kullanımını otomatik yönetir. Politika kaydedildiğinde çalışma alanının kaynak ayarları sistem tarafından belirlenir.' : 'The application manages resource use automatically. When you save this policy, the system determines the work area resource settings.'}</p>
    <p>{schemaName} · {tr ? 'Yalnız bu projenin bu fiziksel şemasındaki çalışma tablolarını sınırlar; veritabanı yetkisi vermez. Politika değişikliği sonrasında yayını yeniden simüle edin.' : 'Limits work tables for this project and physical schema only; does not grant database privileges. Simulate the publication again after a policy change.'}</p>
    {loading && <p>{tr ? 'Yükleniyor…' : 'Loading…'}</p>}
    {error && <div className="error-banner" role="alert">{error}</div>}
    {saved && <p role="status">{tr ? 'Çalışma alanı politikası kaydedildi.' : 'Work area policy saved.'}</p>}
    {view && <form onSubmit={event => void save(event)}>
      <div className="form-grid two-column">
        <label>{tr ? 'Çalışma alanını aç' : 'Enable work area'}<Switch aria-label={tr ? 'Çalışma alanını aç' : 'Enable work area'} checked={view.policy.enabled} disabled={disabled} onChange={value => update('enabled', value)} /></label>
        <label>{tr ? 'Hedef şemada çalışma tablosuna izin ver' : 'Allow work tables in target schema'}<Switch aria-label={tr ? 'Hedef şemada çalışma tablosuna izin ver' : 'Allow work tables in target schema'} checked={view.policy.allowSameSchema} disabled={disabled} onChange={value => update('allowSameSchema', value)} /></label>
        {limits.map(([key, en, turkish, maximum]) => <label key={key}>{tr ? turkish : en}<input className="ant-input" type="number" min={1} max={maximum} step={1} required value={view.policy[key]} disabled={disabled} onChange={event => update(key, Number(event.target.value))} /></label>)}
      </div>
      {canManage && <Button type="submit" tone="primary" busy={saving} disabled={loading || saving || !valid}>{tr ? 'Çalışma Alanı Politikasını Kaydet' : 'Save Work Area Policy'}</Button>}
    </form>}
    {error && <Button type="button" disabled={loading || saving} onClick={() => setRevision(value => value + 1)}>{tr ? 'Politikayı Yeniden Yükle' : 'Reload Policy'}</Button>}
  </section>
}
