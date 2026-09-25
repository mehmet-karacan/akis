import { useEffect, useId, useState } from 'react'
import { Alert, Checkbox, Col, Form, Input, Row, Select, Space } from 'antd'
import { KnowledgeIntegerInput } from './KnowledgeIntegerInput'
import { ArrowRight, Database, Layers3, SlidersHorizontal, Target } from 'lucide-react'
import { definitionsApi } from './api'
import type { MappingContent } from './types'
import { useDefinitionsI18n } from './i18n'
import { knowledgeOptionsForContent, optionDefaults, type KnowledgeOptionDefinition } from './knowledgeModuleOptions'

interface ModuleChoice { uuid: string; hash: string; label: string; kind: string; sourceTechnology?: string; targetTechnology?: string; options: KnowledgeOptionDefinition[] }
interface Pin { versionUuid: string; contentHash: string }
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}

export function MappingKmOptions({ projectUuid, value, sourceTechnologies = [], targetTechnology, onChange }: { projectUuid: string; value: MappingContent; sourceTechnologies?: string[]; targetTechnology?: string; onChange: (value: MappingContent) => void }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const id = useId()
  const [modules, setModules] = useState<ModuleChoice[]>([])
  const [error, setError] = useState(false)
  useEffect(() => {
    let active = true
    setModules([]); setError(false)
    void definitionsApi.listKnowledgeModuleVersions(projectUuid).then(versions => versions
      .filter(version => version.schemaVersion === 2 && ['AKIS_KM/1', 'AKIS_KM/2', 'AKIS_KM/3'].includes(String(record(version.content).language)))
      .map(version => { const content = record(version.content); const technology = record(content.technology); return { uuid: version.uuid, hash: version.contentHash, label: `${version.definitionName} · v${version.versionNumber}`, kind: String(content.kmType), sourceTechnology: typeof technology.source === 'string' ? technology.source : undefined, targetTechnology: typeof technology.target === 'string' ? technology.target : undefined, options: knowledgeOptionsForContent(content) } }))
      .then(nextModules => { if (active) setModules(nextModules) })
      .catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [projectUuid])
  const pins = record(value.modules) as Record<string, Pin>
  const moduleOptions = record(value.moduleOptions)
  const sourceNames = value.sources.map(source => source.alias || source.id)
  const targetNames = [value.target.alias || value.target.id]
  function chooseModule(role: string, uuid?: string) {
    const nextPins = { ...pins }; const nextModuleOptions = { ...moduleOptions }
    const module = modules.find(item => item.uuid === uuid)
    if (module) { nextPins[role] = { versionUuid: module.uuid, contentHash: module.hash }; nextModuleOptions[role] = optionDefaults(module.options) }
    else { delete nextPins[role]; delete nextModuleOptions[role] }
    onChange({ ...value, modules: nextPins, moduleOptions: nextModuleOptions })
  }
  function setModuleOption(role: string, key: string, next: unknown) {
    const values = { ...record(moduleOptions[role]) }
    if (next == null) delete values[key]
    else values[key] = next
    onChange({ ...value, moduleOptions: { ...moduleOptions, [role]: values } })
  }
  const roles = [['loading', 'LKM', tr ? 'Kaynak → Staging' : 'Source → Staging'], ['checking', 'CKM', tr ? 'Staging Kontrolü' : 'Staging Check'], ['integration', 'IKM', tr ? 'Staging → Hedef' : 'Staging → Target']] as const
  const compatible = (module: ModuleChoice, role: string) => {
    const expectedSources = role === 'loading' ? sourceTechnologies : targetTechnology ? [targetTechnology] : []
    return (!module.sourceTechnology || expectedSources.length === 0 || expectedSources.every(type => type === module.sourceTechnology))
      && (!module.targetTechnology || !targetTechnology || module.targetTechnology === targetTechnology)
  }
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <section className="mapping-execution-flow" aria-label={tr ? 'Kaynak staging hedef akışı' : 'Source staging target flow'}>
      <article><Database aria-hidden="true" /><span><small>{tr ? 'Kaynak' : 'Source'}</small><strong>{sourceNames.join(', ') || (tr ? 'Kaynak seçilmedi' : 'No source selected')}</strong></span></article><ArrowRight className="mapping-flow-arrow" aria-hidden="true" />
      <article><Layers3 aria-hidden="true" /><span><small>Staging</small><strong>{tr ? 'Bağlantının çalışma şeması' : 'Connection work schema'}</strong></span></article><ArrowRight className="mapping-flow-arrow" aria-hidden="true" />
      <article><Target aria-hidden="true" /><span><small>{tr ? 'Hedef' : 'Target'}</small><strong>{targetNames.join(', ') || (tr ? 'Hedef seçilmedi' : 'No target selected')}</strong></span></article>
    </section>
    <Alert type="info" showIcon title={tr ? 'Yazma Davranışı IKM’den Gelir' : 'Write Behavior Comes From the IKM'} description={tr ? 'APPEND, MERGE, TRUNCATE_LOAD veya atomik yenileme davranışı ile gerekli anahtar kolonlar seçilen IKM’nin seçenek sözleşmesine göre belirlenir. Seçilen KM sürümleri ve değerler yayın planında sabitlenir.' : 'APPEND, MERGE, TRUNCATE_LOAD or atomic refresh behavior and required key columns are determined by the selected IKM option contract. KM versions and values are pinned into the publication plan.'} />
    {error && <Alert type="error" title={tr ? 'Şemalar veya KM sürümleri yüklenemedi.' : 'Could not load schemas or KM versions.'} />}
    <Form layout="vertical" component="div"><Row gutter={[16, 8]}>
      {roles.map(([role, kind, route]) => <Col key={role} xs={24} lg={8}><Form.Item label={`${kind} · ${route}${role === 'checking' ? (tr ? ' (İsteğe Bağlı)' : ' (Optional)') : ''}`} htmlFor={`${id}-${role}`}><Select id={`${id}-${role}`} allowClear={role === 'checking'} value={pins[role]?.versionUuid} showSearch optionFilterProp="label" placeholder={tr ? 'Uyumlu modül seçin' : 'Select a compatible module'} options={modules.filter(module => module.kind === kind && compatible(module, role)).map(module => ({ value: module.uuid, label: `${module.label} · ${module.sourceTechnology ?? '*'} → ${module.targetTechnology ?? '*'}` }))} onChange={uuid => chooseModule(role, uuid)} /></Form.Item></Col>)}
    </Row></Form>
    <section className="mapping-km-runtime-options" aria-labelledby={`${id}-runtime-options`}>
      <header><SlidersHorizontal size={17} /><span><strong id={`${id}-runtime-options`}>{tr ? 'KM Çalıştırma Seçenekleri' : 'KM Runtime Options'}</strong><small>{tr ? 'Alanlar seçilen KM sürümünden dinamik üretilir.' : 'Fields are generated dynamically from the selected KM version.'}</small></span></header>
      <div className="mapping-km-option-groups" data-empty-text={tr ? 'KM seçenekleri, modül seçildiğinde burada görünür.' : 'KM options appear here once a module is selected.'}>{roles.map(([role, kind, route]) => {
        const selected = modules.find(module => module.uuid === pins[role]?.versionUuid)
        if (!selected?.options.length) return null
        const values = optionDefaults(selected.options, record(moduleOptions[role]))
        return <fieldset key={role}><legend>{kind} · {route}</legend>{selected.options.map(definition => <Form.Item key={definition.key} required={definition.required} label={definition.label} extra={definition.description || undefined}>
          {definition.type === 'BOOLEAN' ? <><Checkbox checked={values[definition.key] === true} onChange={event => setModuleOption(role, definition.key, event.target.checked)}>{values[definition.key] === true ? (tr ? 'Etkin' : 'Enabled') : (tr ? 'Kapalı' : 'Disabled')}</Checkbox>{role === 'integration' && definition.key === 'TRUNCATE_TARGET' && values[definition.key] === true ? <Alert className="mapping-option-risk" type="warning" showIcon title={tr ? 'Geri alınamaz DDL' : 'Non-reversible DDL'} description={tr ? 'Oracle TRUNCATE TABLE örtük commit yapar ve atomik geri alma kapsamında değildir.' : 'Oracle TRUNCATE TABLE performs an implicit commit and cannot be rolled back atomically.'} /> : null}</> : definition.type === 'INTEGER' ? <KnowledgeIntegerInput label={definition.label} value={values[definition.key]} onChange={next => setModuleOption(role, definition.key, next)} /> : definition.type === 'ENUM' ? <Select value={typeof values[definition.key] === 'string' ? values[definition.key] as string : undefined} options={(definition.values ?? []).map(item => ({ value: item, label: item }))} onChange={next => setModuleOption(role, definition.key, next)} /> : <Input value={String(values[definition.key] ?? '')} placeholder={definition.type === 'SQL_HINT' ? 'PARALLEL(4)' : undefined} onChange={event => setModuleOption(role, definition.key, event.target.value)} />}
        </Form.Item>)}</fieldset>
      })}</div>
    </section>
  </Space>
}
