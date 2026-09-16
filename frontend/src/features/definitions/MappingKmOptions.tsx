import { useEffect, useId, useState } from 'react'
import { Alert, Col, Form, InputNumber, Row, Select, Space, Switch } from 'antd'
import { ArrowRight, Database, Layers3, Target } from 'lucide-react'
import { definitionsApi } from './api'
import { topologyApi, type LogicalSchema } from '../topology/api'
import type { MappingContent } from './types'
import { useDefinitionsI18n } from './i18n'

interface ModuleChoice { uuid: string; hash: string; label: string; kind: string }
interface Pin { versionUuid: string; contentHash: string }
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
export function MappingKmOptions({ projectUuid, value, onChange }: { projectUuid: string; value: MappingContent; onChange: (value: MappingContent) => void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const id = useId()
  const [schemas, setSchemas] = useState<LogicalSchema[]>([])
  const [modules, setModules] = useState<ModuleChoice[]>([])
  const [error, setError] = useState(false)
  useEffect(() => {
    let active = true
    setSchemas([]); setModules([]); setError(false)
    void Promise.all([topologyApi.listLogicalSchemas(projectUuid), definitionsApi.listDefinitions(projectUuid, 'KNOWLEDGE_MODULE').then(async definitions =>
      (await Promise.all(definitions.map(async definition => (await definitionsApi.listVersions(projectUuid, definition.uuid))
        .filter(version => version.schemaVersion === 2 && record(version.content).language === 'AKIS_KM/1')
        .map(version => ({ uuid: version.uuid, hash: version.contentHash, label: `${definition.name} · v${version.versionNumber}`, kind: String(record(version.content).kmType) }))))).flat())])
      .then(([nextSchemas, nextModules]) => { if (active) { setSchemas(nextSchemas); setModules(nextModules) } })
      .catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [projectUuid])
  const staging = record(value.staging)
  const pins = record(value.modules) as Record<string, Pin>
  const options = record(value.options)
  const sourceNames = value.datasets.filter(dataset => dataset.role === 'SOURCE').map(dataset => dataset.name || dataset.id)
  const targetNames = value.datasets.filter(dataset => dataset.role === 'TARGET').map(dataset => dataset.name || dataset.id)
  const stagingSchema = schemas.find(schema => schema.uuid === staging.logicalSchemaUuid)
  function option(key: string, next: unknown) { onChange({ ...value, options: { ...options, [key]: next } }) }
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <section className="mapping-execution-flow" aria-label={tr ? 'Kaynak staging hedef akışı' : 'Source staging target flow'}>
      <article><Database aria-hidden="true" /><span><small>{tr ? 'Kaynak' : 'Source'}</small><strong>{sourceNames.join(', ') || (tr ? 'Kaynak seçilmedi' : 'No source selected')}</strong></span></article>
      <ArrowRight className="mapping-flow-arrow" aria-hidden="true" />
      <article><Layers3 aria-hidden="true" /><span><small>Staging</small><strong>{stagingSchema?.name || (tr ? 'Staging şeması seçin' : 'Select staging schema')}</strong></span></article>
      <ArrowRight className="mapping-flow-arrow" aria-hidden="true" />
      <article><Target aria-hidden="true" /><span><small>{tr ? 'Hedef' : 'Target'}</small><strong>{targetNames.join(', ') || (tr ? 'Hedef seçilmedi' : 'No target selected')}</strong></span></article>
    </section>
    <Alert type="warning" showIcon title={tr ? 'Atomik Tam Yenileme' : 'Atomic Full Refresh'} description={tr ? 'Bu strateji hedefin tamamını yeniler. Önce çalışma tablosu yüklenir, kontrol edilir ve sonra hedefe uygulanır. Modüllerin sabit sürümleri kullanılır; kaydetmek yürütme onayı değildir.' : 'This strategy replaces the entire target after loading and checking the stage. Module versions are pinned; saving is not execution approval.'} />
    {error && <Alert type="error" title={tr ? 'Şemalar veya KM sürümleri yüklenemedi.' : 'Could not load schemas or KM versions.'} />}
    <Form layout="vertical" component="div"><Row gutter={[16, 8]}>
      <Col xs={24} lg={6}><Form.Item label={tr ? 'Staging Mantıksal Şeması' : 'Staging Logical Schema'} htmlFor={`${id}-schema`}>
        <Select id={`${id}-schema`} value={String(staging.logicalSchemaUuid || '') || undefined} showSearch optionFilterProp="label" options={schemas.map(schema => ({ value: schema.uuid, label: schema.name }))}
          onChange={logicalSchemaUuid => onChange({ ...value, staging: { logicalSchemaUuid } })} />
      </Form.Item></Col>
      {([['loading', 'LKM', tr ? 'Kaynak → Staging' : 'Source → Staging'], ['checking', 'CKM', tr ? 'Staging Kontrolü' : 'Staging Check'], ['integration', 'IKM', tr ? 'Staging → Hedef' : 'Staging → Target']] as const).map(([role, kind, route]) => <Col key={role} xs={24} lg={6}>
        <Form.Item label={`${kind} · ${route}${role === 'checking' ? (tr ? ' (İsteğe Bağlı)' : ' (Optional)') : ''}`} htmlFor={`${id}-${role}`}>
          <Select id={`${id}-${role}`} allowClear={role === 'checking'} value={pins[role]?.versionUuid} showSearch optionFilterProp="label" options={modules.filter(module => module.kind === kind).map(module => ({ value: module.uuid, label: module.label }))}
            onChange={uuid => { const next = { ...pins }; const module = modules.find(item => item.uuid === uuid); if (module) next[role] = { versionUuid: module.uuid, contentHash: module.hash }; else delete next[role]; onChange({ ...value, modules: next }) }} />
        </Form.Item>
      </Col>)}
      {(['batchRows', 'fetchRows', 'maxRows', 'maxBytes'] as const).map((key, index) => <Col key={key} xs={12} lg={6}>
        <Form.Item label={(tr ? ['Paket Satır Sayısı', 'Okuma Paket Boyutu', 'Toplam Satır Sınırı', 'Toplam Byte Sınırı'] : ['Batch Rows', 'Fetch Rows', 'Total Row Limit', 'Total Byte Limit'])[index]} htmlFor={`${id}-${key}`}>
          <InputNumber id={`${id}-${key}`} style={{ width: '100%' }} min={1} max={index < 2 ? 5000 : key === 'maxRows' ? 100_000_000 : 1_099_511_627_776} precision={0} value={typeof options[key] === 'number' ? options[key] as number : null} onChange={next => option(key, next)} />
        </Form.Item>
      </Col>)}
      <Col span={24}><Space><Switch aria-label={tr ? 'Boş Kaynağa İzin Ver' : 'Allow Empty Source'} checked={options.allowEmptySource === true} onChange={checked => option('allowEmptySource', checked)} /><span>{tr ? 'Boş kaynakta da hedefi tamamen temizle' : 'Clear the entire target even when the source is empty'}</span></Space></Col>
    </Row></Form>
  </Space>
}
