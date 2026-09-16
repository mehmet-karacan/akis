import { useEffect, useId, useRef, useState } from 'react'
import { Alert, Col, Form, InputNumber, Row, Space, Switch } from 'antd'
import { Save } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { Button } from '../../core/ui/Button'

interface Policy {
  enabled: boolean; allowSameSchema: boolean; maxObjects: number; maxRowsPerRun: number
  maxBytesPerRun: number; retentionHours: number
}
interface View { policy: Policy; version: number }
const fields = ['maxObjects', 'maxRowsPerRun', 'maxBytesPerRun', 'retentionHours'] as const
const limits = [1000, 100_000_000, 1_099_511_627_776, 8760]

export function WorkAreaPolicyEditor({ projectUuid, schemaUuid, canWrite }: {
  projectUuid: string; schemaUuid: string; canWrite: boolean
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const id = useId()
  const url = `/api/v1/projects/${encodeURIComponent(projectUuid)}/physical-schemas/${encodeURIComponent(schemaUuid)}/work-area-policy`
  const currentUrl = useRef(url); currentUrl.current = url
  const [view, setView] = useState<View | null>(null)
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    setView(null); setPolicy(null); setError(''); setBusy(false)
    void apiRequest<View>(url).then(value => { if (active) { setView(value); setPolicy(value.policy) } })
      .catch(() => { if (active) setError(tr ? 'Çalışma alanı ayarları yüklenemedi.' : 'Could not load work area settings.') })
    return () => { active = false }
  }, [url, tr])
  async function save() {
    if (!view || !policy || busy || !canWrite) return
    const requestUrl = url
    setBusy(true); setError('')
    try {
      const saved = await apiRequest<View>(requestUrl, { method: 'PUT', ...jsonBody({ expectedVersion: view.version, policy }) })
      if (currentUrl.current !== requestUrl) return
      setView(saved); setPolicy(saved.policy)
      notifyFeedback(tr ? 'Çalışma alanı ayarları kaydedildi.' : 'Work area settings saved.')
    } catch (reason) {
      if (currentUrl.current !== requestUrl) return
      const message = reason instanceof Error ? reason.message : (tr ? 'Kaydedilemedi.' : 'Save failed.')
      setError(message); notifyFeedback(message, 'error')
    } finally { if (currentUrl.current === requestUrl) setBusy(false) }
  }
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon title={tr ? 'KM Çalışma Alanı' : 'KM Work Area'} description={tr
      ? 'Yalnız DBA tarafından bu iş için hazırlanmış Oracle şemasında etkinleştirin. Kayıt işlemi veritabanı yetkisi vermez veya tablo oluşturmaz. Byte sınırı mantıksal veri boyutudur; Oracle disk kotasının yerine geçmez.'
      : 'Enable only for an Oracle schema prepared by your DBA. Saving grants no database privileges and creates no tables. The byte limit is logical data size, not an Oracle disk quota.'} />
    {error && <Alert type="error" title={error} />}
    {policy && <Form component="div" layout="vertical">
      <Row gutter={[16, 8]}>
        <Col xs={24} md={12}><Form.Item label={tr ? 'Yönetilen Çalışma Tablolarına İzin Ver' : 'Allow Managed Work Tables'} htmlFor={`${id}-enabled`}>
          <Switch id={`${id}-enabled`} disabled={!canWrite || busy} checked={policy.enabled} onChange={enabled => setPolicy({ ...policy, enabled })} />
        </Form.Item></Col>
        <Col xs={24} md={12}><Form.Item label={tr ? 'Hedefle Aynı Şemaya İzin Ver' : 'Allow the Same Schema as Target'} htmlFor={`${id}-same`}>
          <Switch id={`${id}-same`} disabled={!canWrite || busy} checked={policy.allowSameSchema} onChange={allowSameSchema => setPolicy({ ...policy, allowSameSchema })} />
        </Form.Item></Col>
        {fields.map((field, index) => <Col key={field} xs={24} sm={12} xl={6}>
          <Form.Item htmlFor={`${id}-${field}`} label={(tr
            ? ['Azami Çalışma Tablosu', 'Çalıştırma Başına Satır', 'Çalıştırma Başına Byte', 'İnceleme Süresi (Saat)']
            : ['Maximum Work Tables', 'Rows per Run', 'Bytes per Run', 'Review Retention (Hours)'])[index]}>
            <InputNumber id={`${id}-${field}`} min={1} max={limits[index]} precision={0} value={policy[field]} style={{ width: '100%' }}
              disabled={!canWrite || busy} onChange={value => { if (value !== null) setPolicy({ ...policy, [field]: value }) }} />
          </Form.Item>
        </Col>)}
      </Row>
      <small>{tr ? 'Belirsiz veya hatalı nesneler süre dolunca otomatik silinmez; inceleme gerektirir.' : 'Unknown or failed objects are not automatically deleted after retention; review is required.'}</small>
    </Form>}
    {canWrite && <Button type="button" tone="primary" icon={<Save size={16} />} disabled={!view || !policy} busy={busy} onClick={() => void save()}>
      {tr ? 'Çalışma Alanını Kaydet' : 'Save Work Area'}
    </Button>}
  </Space>
}
