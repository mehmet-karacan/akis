import { useEffect, useRef, useState } from 'react'
import { Alert, Checkbox, Input, Select, Space, Table } from 'antd'
import { KnowledgeIntegerInput } from './KnowledgeIntegerInput'
import { ArrowDown, ArrowUp, ListOrdered, Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { apiRequest, jsonBody } from '../../core/api/client'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { Dialog } from '../../core/ui'
import { createKnowledgeModule, executableKnowledgeKinds, type ExecutableKnowledgeKind } from './knowledgeModuleTemplates'
import { useDefinitionsI18n } from './i18n'
import { knowledgeOptionTypes, knowledgeOptionsForContent, knowledgeOptionPresentation, knowledgeOptionToken, replaceKnowledgeOptionLines, type KnowledgeOptionDefinition } from './knowledgeModuleOptions'
import { DATABASE_TYPES } from '../topology/connectionFormModel'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { knowledgeOperations, knowledgeSites, knowledgeStepLabel, moveKnowledgeStep, normalizeKnowledgeStepId, readKnowledgeCommands, readKnowledgeSteps, writeKnowledgeCommands, writeKnowledgeSteps, type KnowledgeCommandDefinition, type KnowledgeStepDefinition } from './knowledgeModuleSteps'

interface Diagnostic { valid: boolean; runnable: boolean; line: number; message: string; program: { steps: { id: string; site: string; operation: string; slot: string; line: number }[]; conditions?: Record<string, string> } | null }
const record = (input: unknown): Record<string, unknown> => input && typeof input === 'object' && !Array.isArray(input) ? input as Record<string, unknown> : {}
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
  const [upgradeOpen, setUpgradeOpen] = useState(false)
  const canonical = ['AKIS_KM/2', 'AKIS_KM/3'].includes(String(value.language))
  const [options, setOptions] = useState(() => knowledgeOptionsForContent(value))
  const generatedOptions = useRef<string | null>(null)
  const generatedLines = useRef<string[]>([])
  const signature = optionSignature(value)
  const steps = readKnowledgeSteps(source)
  const commands = readKnowledgeCommands(source)
  const [selectedStepId, setSelectedStepId] = useState(() => steps[0]?.id ?? '')
  const selectedStepIndex = Math.max(0, steps.findIndex(step => step.id === selectedStepId))
  const selectedStep = steps[selectedStepIndex]
  useEffect(() => { if (!steps.some(step => step.id === selectedStepId)) setSelectedStepId(steps[0]?.id ?? '') }, [source, selectedStepId])
  const updateSteps = (next: KnowledgeStepDefinition[]) => {
    onChange({ ...value, source: writeKnowledgeSteps(source, next, commands.filter(command => next.some(step => step.id === command.stepId))) })
    setResult(null)
  }
  const updateStep = (index: number, patch: Partial<KnowledgeStepDefinition>) => {
    const previous = steps[index]
    if (!previous) return
    const nextStep = { ...previous, ...patch }
    const nextSteps = steps.map((step, position) => position === index ? nextStep : step)
    const nextCommands = commands.map(command => command.stepId === previous.id ? { ...command, stepId: nextStep.id } : command)
    if (selectedStepId === previous.id) setSelectedStepId(nextStep.id)
    onChange({ ...value, source: writeKnowledgeSteps(source, nextSteps, nextCommands) }); setResult(null)
  }
  const updateCommands = (next: KnowledgeCommandDefinition[]) => { onChange({ ...value, source: writeKnowledgeCommands(source, steps, next) }); setResult(null) }
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
    {String(value.language) !== 'AKIS_KM/3' && <Alert type="warning" showIcon title={tr ? 'Bu modül eski KM dilini kullanıyor.' : 'This module uses the legacy KM language.'} description={tr ? 'Mevcut yayınlar çalışmaya devam eder. Taslağı KM/3 görev + komut modeline yükselterek akisRef şablonlarını düzenleyebilirsiniz.' : 'Existing publications remain executable. Upgrade the draft to the KM/3 task and command model to edit akisRef templates.'} action={<Button type="button" tone="secondary" onClick={() => setUpgradeOpen(true)}>{tr ? 'KM/3’e Yükselt' : 'Upgrade to KM/3'}</Button>} />}
    <Dialog open={upgradeOpen} title={tr ? 'KM/3’e Yükselt' : 'Upgrade to KM/3'} closeLabel={tr ? 'Vazgeç' : 'Cancel'} onClose={() => setUpgradeOpen(false)}><p>{tr ? 'Taslak, aynı modül türünün KM/3 şablonuyla değiştirilecek. Kaydedilmiş ve yayınlanmış sürümler etkilenmez.' : 'The draft will be replaced with the KM/3 template of the same module kind. Saved and published versions are not affected.'}</p><footer><Button onClick={() => setUpgradeOpen(false)}>{tr ? 'Vazgeç' : 'Cancel'}</Button><Button tone="primary" onClick={() => { const template = createKnowledgeModule(String(value.kmType ?? 'IKM') as ExecutableKnowledgeKind); onChange({ ...value, ...template, technology: value.technology ?? template.technology, ui: value.ui ?? template.ui }); setResult(null); setUpgradeOpen(false) }}>{tr ? 'Taslağı Yükselt' : 'Upgrade Draft'}</Button></footer></Dialog>
    <section className="km-technology-contract" aria-label={tr ? 'Teknoloji uyumluluğu' : 'Technology compatibility'}>
      <label><span>{tr ? 'Kaynak Teknolojisi' : 'Source Technology'}</span><Select value={String(record(value.technology).source ?? '')} options={DATABASE_TYPES.map(type => ({ value: type, label: databaseProviderVisual(type).label }))} onChange={sourceTechnology => onChange({ ...value, technology: { ...record(value.technology), source: sourceTechnology } })} /></label>
      <label><span>{tr ? 'Hedef / Staging Teknolojisi' : 'Target / Staging Technology'}</span><Select value={String(record(value.technology).target ?? '')} options={DATABASE_TYPES.map(type => ({ value: type, label: databaseProviderVisual(type).label }))} onChange={targetTechnology => onChange({ ...value, technology: { ...record(value.technology), target: targetTechnology } })} /></label>
    </section>
    <section className="km-step-schema" aria-labelledby="km-step-schema-title">
      <header><span><ListOrdered size={18} /><span><strong id="km-step-schema-title">{tr ? 'Modül Adımları' : 'Module Steps'}</strong><small>{tr ? 'Adımlar yukarıdan aşağıya çalışır; çalıştırma detayında bu adlar aynen izlenir.' : 'Steps run from top to bottom; run details follow these names.'}</small></span></span>
        <Button type="button" tone="secondary" icon={<Plus size={15} />} onClick={() => { let number = steps.length + 1; while (steps.some(step => step.id === `YENI_ADIM_${number}`)) number++; const id = `YENI_ADIM_${number}`; const next: KnowledgeStepDefinition[] = [...steps, { id, site: 'STAGING', operation: 'CHECK_NOT_NULL', slot: 'WORK_SOURCE_1', condition: '' }]; setSelectedStepId(id); onChange({ ...value, source: writeKnowledgeSteps(source, next, [...commands, { stepId: id, channel: 'SQL', template: '-- SQL' }]) }); setResult(null) }}>{tr ? 'Adım Ekle' : 'Add Step'}</Button></header>
      <div className="km-step-workbench"><nav aria-label={tr ? 'KM adımları' : 'KM steps'}>{steps.map((step, index) => <button type="button" key={step.id} className={step.id === selectedStep?.id ? 'is-selected' : ''} onClick={() => setSelectedStepId(step.id)}><span className="km-step-order">{index + 1}</span><span><strong>{knowledgeStepLabel(step.id, tr ? 'tr-TR' : 'en-US')}</strong><small>{step.operation} · {commands.filter(command => command.stepId === step.id).length} {tr ? 'komut' : 'commands'}</small></span></button>)}</nav>
        {selectedStep && <article className="km-step-detail"><header><div><small>{tr ? 'SEÇİLİ ADIM' : 'SELECTED STEP'}</small><h3>{knowledgeStepLabel(selectedStep.id, tr ? 'tr-TR' : 'en-US')}</h3></div><span className="km-step-actions"><Button type="button" tone="ghost" icon={<ArrowUp size={15} />} disabled={selectedStepIndex === 0} aria-label={tr ? 'Adımı Yukarı Taşı' : 'Move Step Up'} onClick={() => updateSteps(moveKnowledgeStep(steps, selectedStepIndex, selectedStepIndex - 1))} /><Button type="button" tone="ghost" icon={<ArrowDown size={15} />} disabled={selectedStepIndex === steps.length - 1} aria-label={tr ? 'Adımı Aşağı Taşı' : 'Move Step Down'} onClick={() => updateSteps(moveKnowledgeStep(steps, selectedStepIndex, selectedStepIndex + 1))} /><Button type="button" tone="ghost" icon={<Trash2 size={15} />} aria-label={tr ? 'Adımı Sil' : 'Delete Step'} onClick={() => updateSteps(steps.filter((_, position) => position !== selectedStepIndex))} /></span></header><div className="km-step-fields">
          <label><span>{tr ? 'Adım Adı' : 'Step Name'}</span><Input aria-label={tr ? 'Adım Adı' : 'Step Name'} value={knowledgeStepLabel(selectedStep.id, tr ? 'tr-TR' : 'en-US')} onChange={event => updateStep(selectedStepIndex, { id: normalizeKnowledgeStepId(event.target.value) })} /><small>{selectedStep.id}</small></label>
          <label><span>{tr ? 'Konum' : 'Site'}</span><Select value={selectedStep.site} options={knowledgeSites.map(site => ({ value: site, label: site }))} onChange={site => updateStep(selectedStepIndex, { site })} /></label>
          <label><span>{tr ? 'İşlem' : 'Operation'}</span><Select value={selectedStep.operation} options={knowledgeOperations.map(operation => ({ value: operation, label: operation }))} onChange={operation => updateStep(selectedStepIndex, { operation })} /></label>
          <label><span>{tr ? 'Nesne Slotu' : 'Object Slot'}</span><Input value={selectedStep.slot} onChange={event => updateStep(selectedStepIndex, { slot: normalizeKnowledgeStepId(event.target.value) })} /></label>
          <label><span>{tr ? 'Çalışma Koşulu' : 'Run Condition'}</span><Input value={selectedStep.condition} placeholder={tr ? 'Her zaman' : 'Always'} onChange={event => updateStep(selectedStepIndex, { condition: normalizeKnowledgeStepId(event.target.value) })} /></label>
        </div>{String(value.language) === 'AKIS_KM/3' && <div className="km-step-commands"><header><strong>{tr ? 'Komut Şablonları' : 'Command Templates'}</strong><Button type="button" tone="ghost" icon={<Plus size={14} />} disabled={commands.some(command => command.stepId === selectedStep.id && command.channel === 'SQL')} onClick={() => updateCommands([...commands, { stepId: selectedStep.id, channel: 'SQL', template: '-- SQL' }])}>{tr ? 'Komut Ekle' : 'Add Command'}</Button></header>{commands.filter(command => command.stepId === selectedStep.id).map(command => { const commandIndex = commands.indexOf(command); return <article key={`${command.stepId}:${command.channel}`}><label><span>{tr ? 'Kanal' : 'Channel'}</span><Select value={command.channel} options={['SQL', 'SOURCE_SQL', 'TARGET_SQL'].map(channel => ({ value: channel, label: channel }))} onChange={channel => updateCommands(commands.map((item, position) => position === commandIndex ? { ...item, channel: channel as KnowledgeCommandDefinition['channel'] } : item))} /></label><label><span>{tr ? 'Komut' : 'Command'}</span><Input.TextArea aria-label={`${knowledgeStepLabel(selectedStep.id)} ${command.channel}`} value={command.template} rows={8} spellCheck={false} onChange={event => updateCommands(commands.map((item, position) => position === commandIndex ? { ...item, template: event.target.value } : item))} /></label><Button type="button" tone="ghost" icon={<Trash2 size={14} />} aria-label={`${tr ? 'Komutu Sil' : 'Delete Command'} ${selectedStep.id} ${command.channel}`} onClick={() => updateCommands(commands.filter((_, position) => position !== commandIndex))} /></article> })}</div>}</article>}
      </div>
    </section>
    <details className="km-language-source"><summary>{tr ? 'Gelişmiş KM Dil Kaynağı' : 'Advanced KM Language Source'}</summary><Alert type="info" showIcon title={String(value.language)} description={tr
      ? 'Yapılandırılmış adımlar bu sürümlü dil kaynağına kaydedilir. Elle düzenleme ileri seviye kullanım içindir.'
      : 'Structured steps are stored in this versioned language source. Manual editing is intended for advanced use.'} /><Input.TextArea aria-label={tr ? 'KM Dil Kaynağı' : 'KM Language Source'} value={source} rows={10} spellCheck={false} style={{ fontFamily: 'var(--font-mono)' }} onChange={event => {
      const next = event.target.value
      const kind = next.match(/^MODUL\s+(LKM|IKM|CKM)\s*$/m)?.[1]
      const declaredLanguage = next.match(/^AKIS_KM\/(1|2|3)\s*$/m)?.[0].trim()
      onChange({ ...value, source: next, ...(declaredLanguage ? { language: declaredLanguage } : {}), ...(['AKIS_KM/2', 'AKIS_KM/3'].includes(String(declaredLanguage)) ? { optionSchema: [] } : {}), ...(kind ? { kmType: kind } : {}) })
    }} /></details>
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
      <Alert type="info" showIcon title={tr ? 'Yürütme Seçenekleri' : 'Execution Options'} description={tr ? 'LKM: DISTINCT ve ORACLE_HINT. Çalışma tablosunun adı fiziksel şemanın kayıtlı öneklerinden otomatik üretilir. IKM: WRITE_MODE, KEY_COLUMNS, TRUNCATE_TARGET, DROP_WORK_TABLE ve ORACLE_HINT. TRUNCATE için WRITE_MODE=TRUNCATE_LOAD ve TRUNCATE_TARGET=true birlikte gerekir. DROP_WORK_TABLE varsayılan olarak açıktır ve başarılı hedef aktarımından sonra çalışma tablosunu kaldırır. Özel Boolean seçenekleri adımların EGER koşullarında kullanılabilir.' : 'LKM: DISTINCT and ORACLE_HINT. The work-table name is generated automatically from the physical schema prefixes. IKM: WRITE_MODE, KEY_COLUMNS, TRUNCATE_TARGET, DROP_WORK_TABLE and ORACLE_HINT. TRUNCATE requires WRITE_MODE=TRUNCATE_LOAD together with TRUNCATE_TARGET=true. DROP_WORK_TABLE is enabled by default and removes the work table after a successful target load. Custom Boolean options may control steps through EGER conditions.'} />
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
