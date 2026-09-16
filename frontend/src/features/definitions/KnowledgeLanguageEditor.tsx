import { useRef, useState } from 'react'
import { Alert, Input, Space, Table } from 'antd'
import { ShieldCheck } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { useDefinitionsI18n } from './i18n'

interface Diagnostic { valid: boolean; runnable: boolean; line: number; message: string; program: { steps: { id: string; site: string; operation: string; slot: string; line: number }[] } | null }
export function KnowledgeLanguageEditor({ projectUuid, value, onChange }: { projectUuid: string; value: Record<string, unknown>; onChange(value: unknown): void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const source = String(value.source ?? '')
  const current = useRef(source)
  current.current = source
  const [result, setResult] = useState<{ source: string; diagnostic: Diagnostic } | null>(null)
  const [busy, setBusy] = useState(false)
  async function validate() {
    setBusy(true)
    try {
      const diagnostic = await apiRequest<Diagnostic>(`/api/v1/projects/${encodeURIComponent(projectUuid)}/knowledge-language/validate`, { method: 'POST', ...jsonBody({ source }) })
      if (current.current === source) setResult({ source, diagnostic })
    } catch { notifyFeedback(tr ? 'KM dil kontrolü başarısız.' : 'KM language check failed.', 'error') }
    finally { setBusy(false) }
  }
  const diagnostic = result?.source === source ? result.diagnostic : null
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon title="AKIS_KM/1" description={tr
      ? 'Her adım: ADIM KIMLIK KONUM ISLEM SLOT. -- ile başlayan satırlar yorumdur. Fiziksel tablo adı veya serbest SQL yazılmaz; mapping bağları yorumlama sırasında çözülür.'
      : 'Each step: ADIM ID SITE OPERATION SLOT. Lines starting with -- are comments. Physical names and raw SQL are not allowed; mapping bindings are resolved separately.'} />
    <Input.TextArea aria-label={tr ? 'KM Dil Kaynağı' : 'KM Language Source'} value={source} rows={10} spellCheck={false} style={{ fontFamily: 'var(--font-mono)' }} onChange={event => {
      const next = event.target.value
      const kind = next.match(/^MODUL\s+(LKM|IKM|CKM)\s*$/m)?.[1]
      onChange({ ...value, source: next, ...(kind ? { kmType: kind } : {}) })
    }} />
    <Button type="button" tone="secondary" icon={<ShieldCheck size={16} />} busy={busy} onClick={() => void validate()}>{tr ? 'Dili Doğrula' : 'Validate Language'}</Button>
    {diagnostic && <Alert type={diagnostic.valid ? 'info' : 'error'} showIcon title={diagnostic.message} />}
    {diagnostic?.program && <Table size="small" rowKey="id" pagination={false} scroll={{ x: true }} dataSource={diagnostic.program.steps} columns={[
      { title: tr ? 'Adım' : 'Step', dataIndex: 'id' }, { title: tr ? 'Konum' : 'Site', dataIndex: 'site' },
      { title: tr ? 'İşlem' : 'Operation', dataIndex: 'operation' }, { title: tr ? 'Nesne Slotu' : 'Object Slot', dataIndex: 'slot' },
    ]} />}
  </Space>
}
