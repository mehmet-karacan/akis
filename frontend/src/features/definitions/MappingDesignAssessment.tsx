import { useRef, useState, useEffect } from 'react'
import { Alert, Space } from 'antd'
import { ShieldCheck } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { useDefinitionsI18n } from './i18n'
import { storeMapping } from './mappingAuthoring'
import type { MappingContent } from './types'

interface Assessment {
  shapeSupported: boolean
  executionVerified: boolean
  capability: string
  reasonCode: string | null
  maximumSourceRows: number
  remainingChecks: string[]
}

export function MappingDesignAssessment({ projectUuid, value, schemaVersion = 2, onUpgrade }: {
  projectUuid: string; value: MappingContent; schemaVersion?: number; onUpgrade?: () => void
}) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const [result, setResult] = useState<{ key: string; assessment: Assessment } | null>(null)
  const [busy, setBusy] = useState(false)
  const key = JSON.stringify([projectUuid, schemaVersion, value])
  const current = useRef(key)
  current.current = key
  const request = useRef(0)
  useEffect(() => () => { request.current++ }, [])
  const assessment = result?.key === key ? result.assessment : null

  async function assess() {
    const id = ++request.current
    const submitted = key
    setBusy(true)
    try {
      const response = await apiRequest<Assessment>(`/api/v1/projects/${encodeURIComponent(projectUuid)}/mapping-design/assess`, {
        method: 'POST', ...jsonBody({ schemaVersion, content: storeMapping(value) }),
      })
      if (id === request.current && submitted === current.current) setResult({ key: submitted, assessment: response })
    } catch {
      if (id === request.current) notifyFeedback(tr ? 'Mapping kontrolü tamamlanamadı.' : 'Mapping assessment failed.', 'error')
    } finally {
      if (id === request.current) setBusy(false)
    }
  }

  const reasons: Record<string, string> = tr ? {
    MAPPING_SCHEMA_VERSION_REQUIRED: 'Bu taslak eski şema sürümünde. Sürüm 2’ye açıkça geçiş gerekir.',
    UNSUPPORTED_WRITE_STRATEGY: 'Bu yazma stratejisi mevcut pilot motorda desteklenmiyor.',
    UNSUPPORTED_MAPPING_SHAPE: 'Pilot tek kaynak, tek hedef ve doğrudan kolon eşlemesi gerektirir; ek alan veya ifade desteklenmez.',
    INVALID_MAPPING_CONTENT: 'Mapping tanımını ve zorunlu kolon alanlarını kontrol edin.',
  } : {
    MAPPING_SCHEMA_VERSION_REQUIRED: 'This draft requires an explicit upgrade to schema version 2.',
    UNSUPPORTED_WRITE_STRATEGY: 'This write strategy is not supported by the current pilot.',
    UNSUPPORTED_MAPPING_SHAPE: 'The pilot requires one source, one target and direct column mappings, without extra semantics.',
    INVALID_MAPPING_CONTENT: 'Check the mapping definition and required column fields.',
  }

  return <Space orientation="vertical" size="small" style={{ width: '100%' }}>
    <Space wrap>
      <Button type="button" tone="secondary" icon={<ShieldCheck size={16} />} busy={busy} onClick={() => void assess()}>
        {tr ? 'Motor Uyumluluğunu Kontrol Et' : 'Check Runtime Compatibility'}
      </Button>
      {schemaVersion === 1 && onUpgrade && <Button type="button" tone="secondary" onClick={onUpgrade}>
        {tr ? 'Taslağı Sürüm 2’ye Geçir' : 'Upgrade Draft to Version 2'}
      </Button>}
    </Space>
    <p className="definition-help">{tr
      ? 'Bu kontrol veri çalıştırmaz. Katalog seçimi fiziksel bağ değildir; sürüm bağları, ortam yayını ve canlı ön kontroller ayrıca doğrulanır. Staging akışında LKM, CKM ve IKM seçimleri yayın öncesinde denetlenir.'
      : 'This check does not execute data operations. Catalog selections are not physical bindings; version bindings, publication and live preflight are validated separately. LKM, CKM and IKM selections are validated before publishing a staged flow.'}</p>
    {value.writeStrategy.kind === 'ATOMIC_DELETE_INSERT' && <Alert type="warning" showIcon title={tr
      ? 'Tam yenileme: hedef tablonun tamamı DELETE + INSERT ile değiştirilir. Mevcut pilot sınırı 1.000 kaynak satırıdır.'
      : 'Full refresh replaces the entire target using DELETE + INSERT. The current pilot is limited to 1,000 source rows.'} />}
    {assessment && <Alert showIcon type={assessment.shapeSupported ? 'info' : 'warning'} title={assessment.shapeSupported
      ? (tr ? 'Tanım Yapısı Uyumlu — Çalıştırma Henüz Doğrulanmadı' : 'Compatible Shape — Execution Not Verified')
      : (tr ? 'Yalnız Tanım — Motor Uyumsuzluğu' : 'Definition Only — Runtime Incompatibility')}
      description={assessment.shapeSupported
        ? (tr ? `Kaynak satırı üst sınırı: ${assessment.maximumSourceRows}. Fiziksel bağlar ve ortam kontrol edilmedi.` : `Maximum source rows: ${assessment.maximumSourceRows}. Physical bindings and environment were not checked.`)
        : reasons[assessment.reasonCode ?? ''] ?? (tr ? 'Bu yapı mevcut motor tarafından desteklenmiyor.' : 'This structure is not supported by the current runtime.')} />}
  </Space>
}
