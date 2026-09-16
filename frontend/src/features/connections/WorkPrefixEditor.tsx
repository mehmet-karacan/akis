import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Input, Form, Row, Col, Space } from 'antd'
import { Save, RotateCcw } from 'lucide-react'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { Button } from '../../core/ui/Button'
import { WorkAreaPolicyEditor } from './WorkAreaPolicyEditor'

interface Prefixes { loading: string; integration: string; error: string }
interface View { prefixes: Prefixes; version: number; origin: string }
export function WorkPrefixEditor({ projectUuid, subjectUuid, scope = 'connections', canWrite }: {
  projectUuid: string; subjectUuid: string; scope?: 'connections' | 'physical-schemas'; canWrite: boolean
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const fieldId = useId()
  const [view, setView] = useState<View | null>(null)
  const [prefixes, setPrefixes] = useState<Prefixes>({ loading: 'C$', integration: 'I$', error: 'E$' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const url = `/api/v1/projects/${encodeURIComponent(projectUuid)}/${scope}/${encodeURIComponent(subjectUuid)}/work-prefixes`
  useEffect(() => {
    let active = true
    setView(null); setError('')
    void apiRequest<View>(url).then(value => { if (active) { setView(value); setPrefixes(value.prefixes) } })
      .catch(() => { if (active) setError(tr ? 'Prefix ayarları yüklenemedi.' : 'Could not load prefix settings.') })
    return () => { active = false }
  }, [url, tr])
  const valid = Object.values(prefixes).every(value => /^[A-Z][A-Z0-9_$]{0,7}$/.test(value)) && new Set(Object.values(prefixes)).size === 3
  async function save(inherit = false) {
    if (!view || !canWrite || busy || (!inherit && !valid)) return
    setBusy(true); setError('')
    try {
      const result = await apiRequest<View>(inherit ? `${url}?expectedVersion=${view.version}` : url,
        inherit ? { method: 'DELETE' } : { method: 'PUT', ...jsonBody({ expectedVersion: view.version, prefixes }) })
      setView(result); setPrefixes(result.prefixes)
      notifyFeedback(tr ? 'Prefix ayarları kaydedildi.' : 'Prefix settings saved.')
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : (tr ? 'Kaydedilemedi.' : 'Save failed.')
      setError(message); notifyFeedback(message, 'error')
    } finally { setBusy(false) }
  }
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon title={tr ? 'Çalışma Tablosu Prefixleri' : 'Work Table Prefixes'} description={tr
      ? 'LKM yükleme, IKM entegrasyon ve CKM hata tabloları için adlandırma ayarları. Sabit AKIS_ işareti ve çalıştırmaya özel kimlik motor tarafından eklenir. Ayar kaydı tablo oluşturmaz; staging yürütmesi ayrıca etkinleştirilmelidir.'
      : 'Naming for LKM loading, IKM integration and CKM error tables. AKIS_ and a run-specific identity are added by the engine. Saving settings creates no tables; staging execution must be enabled separately.'} />
    {error && <Alert type="error" title={error} />}
    <Form layout="vertical" component="div">
      <Row gutter={[16, 8]}>{(['loading', 'integration', 'error'] as const).map((key, index) => <Col xs={24} md={8} key={key}>
        <Form.Item htmlFor={`${fieldId}-${key}`} label={(tr ? ['Yükleme / LKM', 'Entegrasyon / IKM', 'Hata / CKM'] : ['Loading / LKM', 'Integration / IKM', 'Error / CKM'])[index]}>
          <Input id={`${fieldId}-${key}`} maxLength={8} disabled={!canWrite || busy || !view} value={prefixes[key]} onChange={event => setPrefixes(current => ({ ...current, [key]: event.target.value.toUpperCase() }))} />
          <small>{`AKIS_${prefixes[key]}_<RUN>`}</small>
        </Form.Item>
      </Col>)}</Row>
    </Form>
    <small>{tr ? '1–8 karakter; A-Z ile başlayıp A-Z, 0-9, _ veya $ içerebilir. Üç prefix farklı olmalıdır.' : '1–8 characters; start with A-Z, followed by A-Z, 0-9, _ or $. All three prefixes must differ.'}</small>
    <Space wrap>
      {canWrite && <Button type="button" tone="primary" icon={<Save size={16} />} disabled={!view || !valid} busy={busy} onClick={() => void save()}>{tr ? 'Prefixleri Kaydet' : 'Save Prefixes'}</Button>}
      {canWrite && scope === 'physical-schemas' && view?.origin === 'SCHEMA' && <Button type="button" icon={<RotateCcw size={16} />} disabled={busy} onClick={() => void save(true)}>{tr ? 'Bağlantı Varsayılanını Kullan' : 'Use Connection Defaults'}</Button>}
      <span>{view ? (tr ? `Ayar kaynağı: ${view.origin === 'SCHEMA' ? 'Fiziksel Şema' : view.origin === 'CONNECTION' ? 'Bağlantı' : 'Platform'}` : `Settings source: ${view.origin}`) : ''}</span>
    </Space>
    {scope === 'physical-schemas' && <WorkAreaPolicyEditor key={subjectUuid} projectUuid={projectUuid} schemaUuid={subjectUuid} canWrite={canWrite} />}
  </Space>
}
