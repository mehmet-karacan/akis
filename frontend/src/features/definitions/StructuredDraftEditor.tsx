import { Checkbox as AntCheckbox } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import { Plus, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { topologyApi, type LogicalSchema } from '../topology/api'
import { definitionCodeLabel, useDefinitionsI18n } from './i18n'
import type { DefinitionType } from './types'
import { KnowledgeModuleEditor } from './KnowledgeModuleEditor'

interface Props { projectUuid?: string; type: DefinitionType; value: unknown; onChange: (value: unknown) => void }
type Content = Record<string, unknown>
const record = (value: unknown): Content => value && typeof value === 'object' && !Array.isArray(value) ? value as Content : {}

export function StructuredDraftEditor({ projectUuid, type, value, onChange }: Props) {
  const { language, t } = useDefinitionsI18n()
  const [schemas, setSchemas] = useState<LogicalSchema[]>([])
  const [schemaError, setSchemaError] = useState(false)
  useEffect(() => {
    let active = true
    if (type === 'VARIABLE' && projectUuid) {
      topologyApi.listLogicalSchemas(projectUuid).then(items => { if (active) { setSchemas(items); setSchemaError(false) } })
        .catch(() => { if (active) setSchemaError(true) })
    }
    return () => { active = false }
  }, [projectUuid, type])
  const options = (values: string[]) => values.map((item) => <option key={item} value={item}>{definitionCodeLabel(item, language)}</option>)
  const content = record(value)
  const set = (key: string, next: unknown) => onChange({ ...content, [key]: next })

  if (type === 'VARIABLE') return <div className="structured-draft-form variable-draft-form">
    <label><span>{t('dataType')}</span><FormSelect value={String(content.dataType ?? 'DATE')} onChange={(event) => set('dataType', event.target.value)}>{options(['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'])}</FormSelect></label>
    <label><span>{t('scope')}</span><FormSelect value={String(content.scope ?? 'PROJECT')} onChange={(event) => set('scope', event.target.value)}>{options(['PROJECT', 'PACKAGE_RUN'])}</FormSelect></label>
    <label><span>{t('historyMode')}</span><FormSelect value={String(content.historyMode ?? 'LATEST')} onChange={(event) => set('historyMode', event.target.value)}>{options(['NONE', 'LATEST', 'ALL'])}</FormSelect></label>
    <label><span>{t('valueSource')}</span><FormSelect value={String(content.valueSource ?? 'REFRESH_QUERY')} onChange={(event) => set('valueSource', event.target.value)}>{options(['INPUT', 'REFRESH_QUERY', 'DEFAULT'])}</FormSelect></label>
    {content.valueSource === 'REFRESH_QUERY' && <label className="structured-draft-wide">
      <span>{t('logicalSchema')}</span>
      <FormSelect aria-label={t('logicalSchema')} value={String(content.logicalSchemaUuid ?? '')} onChange={event => set('logicalSchemaUuid', event.target.value)}>
        <option value="">{language === 'tr' ? 'Mantıksal Şema Seçin' : 'Select Logical Schema'}</option>
        {schemas.map(schema => <option key={schema.uuid} value={schema.uuid}>{schema.name} ({schema.code})</option>)}
      </FormSelect>
      <small>{schemaError ? (language === 'tr' ? 'Mantıksal şemalar yüklenemedi.' : 'Logical schemas could not be loaded.') : (language === 'tr' ? 'Ortam çalıştırmadan alınır. Sorgu, bu şemanın o ortamdaki bağlantısında çalışır.' : 'The run supplies the environment. The query uses this schema’s mapped connection.')}</small>
    </label>}
    {content.valueSource === 'DEFAULT' && <label className="structured-draft-wide"><span>{t('defaultValue')}</span><AntInput value={String(content.defaultValue ?? '')} onChange={(event) => set('defaultValue', event.target.value)} /></label>}
    {content.valueSource === 'REFRESH_QUERY' && <label className="structured-draft-wide"><span>{t('sqlCommand')}</span><AntInput.TextArea spellCheck={false} value={String(content.query ?? 'SELECT SYSDATE - 1 FROM DUAL')} onChange={(event) => set('query', event.target.value)} /></label>}
  </div>

  if (type === 'SEQUENCE') return <div className="structured-draft-form">
    <label><span>{t('implementation')}</span><FormSelect value={String(content.implementation ?? 'REPOSITORY')} onChange={(event) => set('implementation', event.target.value)}>{options(['REPOSITORY', 'NATIVE'])}</FormSelect></label>
    <label><span>{t('startValue')}</span><AntInput type="number" value={Number(content.start ?? 1)} onChange={(event) => set('start', Number(event.target.value))} /></label>
    <label><span>{t('incrementValue')}</span><AntInput type="number" value={Number(content.increment ?? 1)} onChange={(event) => set('increment', Number(event.target.value))} /></label>
    <label className="procedure-checkbox"><AntCheckbox  checked={content.cycle === true} onChange={(event) => set('cycle', event.target.checked)} /><span>{t('cycle')}</span></label>
  </div>

  if (type === 'USER_FUNCTION') {
    const parameters = Array.isArray(content.parameters) ? content.parameters.map(record) : []
    return <div className="structured-draft-form">
      <label><span>{t('returnType')}</span><FormSelect value={String(content.returnType ?? 'STRING')} onChange={(event) => set('returnType', event.target.value)}>{options(['STRING', 'NUMBER', 'DATE', 'BOOLEAN'])}</FormSelect></label>
      <label className="structured-draft-wide"><span>{t('implementation')}</span><AntInput.TextArea spellCheck={false} value={String(content.expression ?? '')} onChange={(event) => set('expression', event.target.value)} /></label>
      <section className="structured-draft-wide structured-list-editor"><header><strong>{t('parameters')}</strong><AntActionButton tone="secondary" type="button" onClick={() => set('parameters', [...parameters, { name: `PARAM_${parameters.length + 1}`, dataType: 'STRING' }])}><Plus size={15} />{t('addParameter')}</AntActionButton></header>{parameters.map((parameter, index) => <div key={index}><AntInput aria-label={t('parameterName')} value={String(parameter.name ?? '')} onChange={(event) => set('parameters', parameters.map((item, position) => position === index ? { ...item, name: event.target.value } : item))} /><FormSelect aria-label={t('dataType')} value={String(parameter.dataType ?? 'STRING')} onChange={(event) => set('parameters', parameters.map((item, position) => position === index ? { ...item, dataType: event.target.value } : item))}>{options(['STRING', 'NUMBER', 'DATE', 'BOOLEAN'])}</FormSelect><AntActionButton tone="ghost" className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => set('parameters', parameters.filter((_, position) => position !== index))}><Trash2 size={15} /></AntActionButton></div>)}</section>
    </div>
  }

  if (type === 'KNOWLEDGE_MODULE') {
    return <KnowledgeModuleEditor value={content} onChange={onChange} />
  }

  if (type === 'LOAD_PLAN') {
    const steps = Array.isArray(content.steps) ? content.steps.map(record) : []
    return <div className="structured-draft-form"><label><span>{t('restartPolicy')}</span><FormSelect value={String(content.restartPolicy ?? 'FAILED_STEP')} onChange={(event) => set('restartPolicy', event.target.value)}><option value="FAILED_STEP">{t('failedStep')}</option><option value="FROM_START">{t('fromStart')}</option></FormSelect></label><section className="structured-draft-wide structured-list-editor"><header><strong>{t('steps')}</strong><AntActionButton tone="secondary" type="button" onClick={() => set('steps', [...steps, { id: `STEP_${steps.length + 1}`, type: 'SCENARIO', scenarioVersionUuid: '' }])}><Plus size={15} />{t('addStep')}</AntActionButton></header>{steps.map((step, index) => <div key={index}><AntInput aria-label={t('stepId')} value={String(step.id ?? '')} onChange={(event) => set('steps', steps.map((item, position) => position === index ? { ...item, id: event.target.value } : item))} /><AntInput aria-label={t('scenarioVersionReference')} placeholder={t('scenarioVersionReference')} value={String(step.scenarioVersionUuid ?? '')} onChange={(event) => set('steps', steps.map((item, position) => position === index ? { ...item, scenarioVersionUuid: event.target.value } : item))} /><AntActionButton tone="ghost" className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => set('steps', steps.filter((_, position) => position !== index))}><Trash2 size={15} /></AntActionButton></div>)}</section></div>
  }

  if (type === 'PACKAGE') {
    const steps = Array.isArray(content.steps) ? content.steps.map(record) : []
    const replaceSteps = (next: Content[]) => set('steps', next)
    return <div className="structured-package-editor">
      <label><span>{t('firstStep')}</span><FormSelect value={String(content.firstStepId ?? '')} onChange={(event) => set('firstStepId', event.target.value)}>{steps.map((step, index) => <option key={index} value={String(step.id ?? '')}>{String(step.id ?? '')}</option>)}</FormSelect></label>
      <header><div><h3>{t('packageSteps')}</h3><p>{t('packageStepsHint')}</p></div><AntActionButton tone="secondary" type="button" onClick={() => { const id = `STEP_${steps.length + 1}`; onChange({ ...content, steps: [...steps, { id, type: 'PROCEDURE' }], firstStepId: content.firstStepId || id }) }}><Plus size={15} />{t('addStep')}</AntActionButton></header>
      <div className="structured-package-list">{steps.map((step, index) => <article key={index}>
        <span className="procedure-step-number">{index + 1}</span>
        <label><span>{t('stepId')}</span><AntInput value={String(step.id ?? '')} onChange={(event) => replaceSteps(steps.map((item, position) => position === index ? { ...item, id: event.target.value } : item))} /></label>
        <label><span>{t('type')}</span><FormSelect value={String(step.type ?? 'PROCEDURE')} onChange={(event) => replaceSteps(steps.map((item, position) => position === index ? { ...item, type: event.target.value } : item))}><option>MAPPING</option><option>PROCEDURE</option><option>PACKAGE</option><option>SCENARIO</option></FormSelect></label>
        <AntActionButton tone="ghost" className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => replaceSteps(steps.filter((_, position) => position !== index))}><Trash2 size={15} /></AntActionButton>
      </article>)}</div>
    </div>
  }
  return <div className="definition-state"><p>{t('advancedEditorRequired')}</p></div>
}
