import { useEffect, useRef, useState } from 'react'
import { Alert, Checkbox, Input, Select, Space, Table } from 'antd'
import { KnowledgeIntegerInput } from './KnowledgeIntegerInput'
import { Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { Dialog } from '../../core/ui'
import { createKnowledgeModule, executableKnowledgeKinds, type ExecutableKnowledgeKind } from './knowledgeModuleTemplates'
import { useDefinitionsI18n } from './i18n'
import { knowledgeOptionTypes, knowledgeOptionsForContent, knowledgeOptionPresentation, knowledgeOptionToken, replaceKnowledgeOptionLines, type KnowledgeOptionDefinition } from './knowledgeModuleOptions'

interface Diagnostic { valid: boolean; runnable: boolean; line: number; message: string; program: { steps: { id: string; site: string; operation: string; slot: string; line: number }[]; conditions?: Record<string, string> } | null }
const optionSignature = (value: Record<string, unknown>) => JSON.stringify([value.source, value.language, value.optionSchema, value.ui])
export function KnowledgeLanguageEditor({ projectUuid, value, onChange }: { projectUuid: string; value: Record<string, unknown>; onChange(value: unknown): void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const source = String(value.source ?? '')
  const current = useRef(source)
  current.current = source
  const [result, setResult] = useState<{ source: string; diagnostic: Diagnostic } | null>(null)
  const [busy, setBusy] = useState(false)
  const [pendingKind, setPendingKind] = useState<ExecutableKnowledgeKind | null>(null)
  const canonical = String(value.language) === 'AKIS_KM/2'
  const [options, setOptions] = useState(() => knowledgeOptionsForContent(value))
  const generatedOptions = useRef<string | null>(null)
  const generatedLines = useRef<string[]>([])
  const signature = optionSignature(value)
  useEffect(() => {
    if (signature !== generatedOptions.current) setOptions(knowledgeOptionsForContent(value))
  }, [signature])
  const updateOptions = (next: KnowledgeOptionDefinition[]) => {
    const apply = (nextValue: Record<string, unknown>) => { generatedOptions.current = optionSignature(nextValue); setOptions(next); onChange(nextValue) }
    if (!canonical) { apply({ ...value, optionSchema: next }); return }
    const declarations = next.map(option => {
      const fallback = option.defaultValue === undefined || option.defaultValue === '' ? 'YOK' : knowledgeOptionToken(String(option.defaultValue))
      const values = option.type === 'ENUM' && option.values?.length ? knowledgeOptionToken(option.values.join(',')) : 'YOK'
      return `SECENEK ${option.key} ${option.type} ${option.required ? 'ZORUNLU' : 'ISTEGE_BAGLI'} ${fallback} ${values}`
    })
    const nextSource = replaceKnowledgeOptionLines(source, declarations, generatedOptions.current === signature ? generatedLines.current : [])
    generatedLines.current = declarations
    apply({ ...value, source: nextSource, optionSchema: [], ui: knowledgeOptionPresentation(value, next) })
  }
  async function validate() {
    setBusy(true)
    try {
      const diagnostic = await apiRequest<Diagnostic>(`/api/v1/projects/${encodeURIComponent(projectUuid)}/knowledge-language/validate`, { method: 'POST', ...jsonBody({ source }) })
      if (current.current === source) setResult({ source, diagnostic })
    } catch { notifyFeedback(tr ? 'KM dil kontrolü başarısız.' : 'KM language check failed.', 'error') }
    finally { setBusy(false) }
  }
  const diagnostic = result?.source === source ? result.diagnostic : null
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }} className="km-language-editor">
    <label className="km-type"><span>{tr ? 'Modül Türü ve Şablonu' : 'Module Type and Template'}</span><Select aria-label={tr ? 'Modül Türü ve Şablonu' : 'Module Type and Template'} value={String(value.kmType)} options={executableKnowledgeKinds.map(kind => ({ value: kind, label: `${kind} · ${kind === 'LKM' ? (tr ? 'Yükleme' : 'Loading') : kind === 'CKM' ? (tr ? 'Veri Kontrolü' : 'Data Check') : (tr ? 'Entegrasyon' : 'Integration')}` }))} onChange={kind => { if (kind !== value.kmType) setPendingKind(kind as ExecutableKnowledgeKind) }} /></label>
    <Dialog open={pendingKind !== null} title={tr ? 'Modül Şablonunu Değiştir' : 'Replace Module Template'} closeLabel={tr ? 'Vazgeç' : 'Cancel'} onClose={() => setPendingKind(null)}>
      <p>{tr ? 'Mevcut taslaktaki adımlar ve seçenekler seçilen şablonla değiştirilecek. Kaydedilmiş sürümler değişmez. Devam edilsin mi?' : 'Current draft steps and options will be replaced by the selected template. Saved versions are unchanged. Continue?'}</p>
      <footer><Button onClick={() => setPendingKind(null)}>{tr ? 'Vazgeç' : 'Cancel'}</Button><Button tone="primary" onClick={() => { if (pendingKind) { onChange({ ...value, ...createKnowledgeModule(pendingKind) }); setResult(null) } setPendingKind(null) }}>{tr ? 'Şablonu Uygula' : 'Apply Template'}</Button></footer>
    </Dialog>
    <Alert type="info" showIcon title={canonical ? 'AKIS_KM/2' : 'AKIS_KM/1'} description={tr
      ? 'Her adım: ADIM KIMLIK KONUM ISLEM SLOT [EGER BOOLEAN_SECENEK]. AKIS_KM/2 içinde önceden tanımlı Boolean seçenek true ise koşullu adım çalışır. Seçenekler yayın planında sabitlenir; zorunlu yükleme ve hedefe yazma sırası korunur. Fiziksel tablo adı, kullanıcı Java kodu veya serbest SQL çalıştırılmaz.'
      : 'Each step: ADIM ID SITE OPERATION SLOT [EGER BOOLEAN_OPTION]. In AKIS_KM/2, a conditional step runs when its previously declared Boolean option is true. Values are pinned at publication; required loading and target-write order is preserved. Physical names, user Java and raw SQL are not executed.'} />
    <Input.TextArea aria-label={tr ? 'KM Dil Kaynağı' : 'KM Language Source'} value={source} rows={10} spellCheck={false} style={{ fontFamily: 'var(--font-mono)' }} onChange={event => {
      const next = event.target.value
      const kind = next.match(/^MODUL\s+(LKM|IKM|CKM)\s*$/m)?.[1]
      const declaredLanguage = next.match(/^AKIS_KM\/(1|2)\s*$/m)?.[0].trim()
      onChange({ ...value, source: next, ...(declaredLanguage ? { language: declaredLanguage } : {}), ...(declaredLanguage === 'AKIS_KM/2' ? { optionSchema: [] } : {}), ...(kind ? { kmType: kind } : {}) })
    }} />
    <Button type="button" tone="secondary" icon={<ShieldCheck size={16} />} busy={busy} onClick={() => void validate()}>{tr ? 'Dili Doğrula' : 'Validate Language'}</Button>
    {diagnostic && <Alert type={diagnostic.valid ? 'info' : 'error'} showIcon title={diagnostic.message} />}
    {diagnostic?.program && <Table size="small" rowKey="id" pagination={false} scroll={{ x: true }} dataSource={diagnostic.program.steps} columns={[
      { title: tr ? 'Adım' : 'Step', dataIndex: 'id' }, { title: tr ? 'Konum' : 'Site', dataIndex: 'site' },
      { title: tr ? 'İşlem' : 'Operation', dataIndex: 'operation' }, { title: tr ? 'Nesne Slotu' : 'Object Slot', dataIndex: 'slot' },
      { title: tr ? 'Çalışma Koşulu' : 'Run Condition', render: (_, step) => diagnostic.program?.conditions?.[step.id] ? `${diagnostic.program.conditions[step.id]} = true` : (tr ? 'Her zaman' : 'Always') },
    ]} />}
    <section className="km-option-schema" aria-labelledby="km-option-schema-title">
      <header><span><strong id="km-option-schema-title">{tr ? 'Çalıştırma Seçenekleri' : 'Runtime Options'}</strong><small>{tr ? 'Mapping bu değerleri KM sürümüne göre ister ve yayın planında sabitler.' : 'Mappings collect these values for the KM version and pin them into the publication plan.'}</small></span>
        <Button type="button" tone="secondary" icon={<Plus size={15} />} disabled={options.length >= 32} onClick={() => { let number = 1; while (options.some(option => option.key === `OPTION_${number}`)) number++; updateOptions([...options, { key: `OPTION_${number}`, label: tr ? 'Yeni Seçenek' : 'New Option', type: 'STRING' }]) }}>{tr ? 'Seçenek Ekle' : 'Add Option'}</Button></header>
      <Alert type="info" showIcon title={tr ? 'Yürütme Seçenekleri' : 'Execution Options'} description={tr ? 'LKM: DISTINCT ve ORACLE_HINT. IKM: WRITE_MODE, KEY_COLUMNS, TRUNCATE_TARGET ve ORACLE_HINT. TRUNCATE için WRITE_MODE=TRUNCATE_LOAD ve TRUNCATE_TARGET=true birlikte gerekir. Özel Boolean seçenekleri adımların EGER koşullarında kullanabilirsiniz. Kullanılmayan seçenekler yürütmeyi değiştirmez.' : 'LKM: DISTINCT and ORACLE_HINT. IKM: WRITE_MODE, KEY_COLUMNS, TRUNCATE_TARGET and ORACLE_HINT. TRUNCATE requires both WRITE_MODE=TRUNCATE_LOAD and TRUNCATE_TARGET=true. Custom Boolean options can control steps through EGER conditions. Unreferenced options do not change execution.'} />
      {options.length === 0 ? <div className="km-options-empty">{tr ? 'Bu modül için seçenek tanımlanmadı.' : 'No options are defined for this module.'}</div> : options.map((option, index) => <fieldset key={index}>
        <legend>{tr ? 'Seçenek' : 'Option'} {index + 1}</legend>
        <label><span>{tr ? 'Anahtar' : 'Key'}</span><Input value={option.key} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, key: event.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') } : item))} /></label>
        <label><span>{tr ? 'Görünen Ad' : 'Display Name'}</span><Input value={option.label} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, label: event.target.value } : item))} /></label>
        <label><span>{tr ? 'Veri Tipi' : 'Data Type'}</span><Select value={option.type} options={knowledgeOptionTypes.map(type => ({ value: type, label: type }))} onChange={type => updateOptions(options.map((item, position) => position === index ? { ...item, type, defaultValue: type === 'BOOLEAN' ? false : '' } : item))} /></label>
        <label className="km-option-description"><span>{tr ? 'Açıklama' : 'Description'}</span><Input value={option.description} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, description: event.target.value } : item))} /></label>
        {option.type === 'ENUM' && <label><span>{tr ? 'İzin Verilen Değerler' : 'Allowed Values'}</span><Input value={(option.values ?? []).join(',')} placeholder="APPEND,MERGE" onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, values: event.target.value.split(',').map(value => value.trim()) } : item))} /></label>}
        <label><span>{tr ? 'Varsayılan' : 'Default'}</span>{option.type === 'BOOLEAN' ? <Checkbox checked={option.defaultValue === true} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, defaultValue: event.target.checked } : item))}>{tr ? 'Etkin' : 'Enabled'}</Checkbox> : option.type === 'INTEGER' ? <KnowledgeIntegerInput value={option.defaultValue} label={tr ? 'Varsayılan' : 'Default'} onChange={next => updateOptions(options.map((item, position) => position === index ? { ...item, defaultValue: next } : item))} /> : <Input value={String(option.defaultValue ?? '')} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, defaultValue: event.target.value } : item))} />}</label>
        <label className="km-option-required"><Checkbox checked={option.required === true} onChange={event => updateOptions(options.map((item, position) => position === index ? { ...item, required: event.target.checked } : item))}>{tr ? 'Zorunlu' : 'Required'}</Checkbox></label>
        <Button type="button" tone="ghost" icon={<Trash2 size={15} />} aria-label={`${tr ? 'Seçeneği Sil' : 'Delete Option'} ${index + 1}`} onClick={() => updateOptions(options.filter((_, position) => position !== index))} />
      </fieldset>)}
    </section>
  </Space>
}
