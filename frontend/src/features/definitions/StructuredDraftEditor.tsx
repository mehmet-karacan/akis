import { Plus, Trash2 } from 'lucide-react'
import { definitionCodeLabel, useDefinitionsI18n } from './i18n'
import type { DefinitionType } from './types'

interface Props { type: DefinitionType; value: unknown; onChange: (value: unknown) => void }
type Content = Record<string, unknown>
const record = (value: unknown): Content => value && typeof value === 'object' && !Array.isArray(value) ? value as Content : {}

export function StructuredDraftEditor({ type, value, onChange }: Props) {
  const { language, t } = useDefinitionsI18n()
  const options = (values: string[]) => values.map((item) => <option key={item} value={item}>{definitionCodeLabel(item, language)}</option>)
  const content = record(value)
  const set = (key: string, next: unknown) => onChange({ ...content, [key]: next })

  if (type === 'VARIABLE') return <div className="structured-draft-form">
    <label><span>{t('dataType')}</span><select value={String(content.dataType ?? 'STRING')} onChange={(event) => set('dataType', event.target.value)}>{options(['STRING', 'NUMBER', 'DATE', 'BOOLEAN'])}</select></label>
    <label><span>{t('scope')}</span><select value={String(content.scope ?? 'PROJECT')} onChange={(event) => set('scope', event.target.value)}>{options(['PROJECT', 'PACKAGE'])}</select></label>
    <label><span>{t('historyMode')}</span><select value={String(content.historyMode ?? 'LATEST')} onChange={(event) => set('historyMode', event.target.value)}>{options(['LATEST', 'HISTORY'])}</select></label>
    <label><span>{t('valueSource')}</span><select value={String(content.valueSource ?? 'INPUT')} onChange={(event) => set('valueSource', event.target.value)}>{options(['INPUT', 'SQL', 'DEFAULT'])}</select></label>
    {content.valueSource === 'DEFAULT' && <label className="structured-draft-wide"><span>{t('defaultValue')}</span><input value={String(content.defaultValue ?? '')} onChange={(event) => set('defaultValue', event.target.value)} /></label>}
    {content.valueSource === 'SQL' && <label className="structured-draft-wide"><span>{t('sqlCommand')}</span><textarea spellCheck={false} value={String(content.query ?? '')} onChange={(event) => set('query', event.target.value)} /></label>}
  </div>

  if (type === 'SEQUENCE') return <div className="structured-draft-form">
    <label><span>{t('implementation')}</span><select value={String(content.implementation ?? 'REPOSITORY')} onChange={(event) => set('implementation', event.target.value)}>{options(['REPOSITORY', 'NATIVE'])}</select></label>
    <label><span>{t('startValue')}</span><input type="number" value={Number(content.start ?? 1)} onChange={(event) => set('start', Number(event.target.value))} /></label>
    <label><span>{t('incrementValue')}</span><input type="number" value={Number(content.increment ?? 1)} onChange={(event) => set('increment', Number(event.target.value))} /></label>
    <label className="procedure-checkbox"><input type="checkbox" checked={content.cycle === true} onChange={(event) => set('cycle', event.target.checked)} /><span>{t('cycle')}</span></label>
  </div>

  if (type === 'USER_FUNCTION') {
    const parameters = Array.isArray(content.parameters) ? content.parameters.map(record) : []
    return <div className="structured-draft-form">
      <label><span>{t('returnType')}</span><select value={String(content.returnType ?? 'STRING')} onChange={(event) => set('returnType', event.target.value)}>{options(['STRING', 'NUMBER', 'DATE', 'BOOLEAN'])}</select></label>
      <label className="structured-draft-wide"><span>{t('implementation')}</span><textarea spellCheck={false} value={String(content.expression ?? '')} onChange={(event) => set('expression', event.target.value)} /></label>
      <section className="structured-draft-wide structured-list-editor"><header><strong>{t('parameters')}</strong><button className="definition-button definition-button--quiet" type="button" onClick={() => set('parameters', [...parameters, { name: `PARAM_${parameters.length + 1}`, dataType: 'STRING' }])}><Plus size={15} />{t('addParameter')}</button></header>{parameters.map((parameter, index) => <div key={index}><input aria-label={t('parameterName')} value={String(parameter.name ?? '')} onChange={(event) => set('parameters', parameters.map((item, position) => position === index ? { ...item, name: event.target.value } : item))} /><select aria-label={t('dataType')} value={String(parameter.dataType ?? 'STRING')} onChange={(event) => set('parameters', parameters.map((item, position) => position === index ? { ...item, dataType: event.target.value } : item))}>{options(['STRING', 'NUMBER', 'DATE', 'BOOLEAN'])}</select><button className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => set('parameters', parameters.filter((_, position) => position !== index))}><Trash2 size={15} /></button></div>)}</section>
    </div>
  }

  if (type === 'KNOWLEDGE_MODULE') {
    const tasks = Array.isArray(content.tasks) ? content.tasks.map(record) : []
    return <div className="structured-draft-form"><label><span>{t('knowledgeModuleType')}</span><select value={String(content.kmType ?? 'IKM')} onChange={(event) => set('kmType', event.target.value)}><option>IKM</option><option>LKM</option><option>CKM</option><option>RKM</option></select></label><section className="structured-draft-wide structured-list-editor"><header><strong>{t('tasks')}</strong><button className="definition-button definition-button--quiet" type="button" onClick={() => set('tasks', [...tasks, { name: `${t('step')} ${tasks.length + 1}`, command: '' }])}><Plus size={15} />{t('addStep')}</button></header>{tasks.map((task, index) => <div key={index} className="structured-list-editor--stack"><input aria-label={t('stepName')} value={String(task.name ?? '')} onChange={(event) => set('tasks', tasks.map((item, position) => position === index ? { ...item, name: event.target.value } : item))} /><textarea aria-label={t('sqlCommand')} spellCheck={false} value={String(task.command ?? '')} onChange={(event) => set('tasks', tasks.map((item, position) => position === index ? { ...item, command: event.target.value } : item))} /><button className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => set('tasks', tasks.filter((_, position) => position !== index))}><Trash2 size={15} /></button></div>)}</section></div>
  }

  if (type === 'LOAD_PLAN') {
    const steps = Array.isArray(content.steps) ? content.steps.map(record) : []
    return <div className="structured-draft-form"><label><span>{t('restartPolicy')}</span><select value={String(content.restartPolicy ?? 'FAILED_STEP')} onChange={(event) => set('restartPolicy', event.target.value)}><option value="FAILED_STEP">{t('failedStep')}</option><option value="FROM_START">{t('fromStart')}</option></select></label><section className="structured-draft-wide structured-list-editor"><header><strong>{t('steps')}</strong><button className="definition-button definition-button--quiet" type="button" onClick={() => set('steps', [...steps, { id: `STEP_${steps.length + 1}`, type: 'SCENARIO', scenarioVersionUuid: '' }])}><Plus size={15} />{t('addStep')}</button></header>{steps.map((step, index) => <div key={index}><input aria-label={t('stepId')} value={String(step.id ?? '')} onChange={(event) => set('steps', steps.map((item, position) => position === index ? { ...item, id: event.target.value } : item))} /><input aria-label={t('scenarioVersionReference')} placeholder={t('scenarioVersionReference')} value={String(step.scenarioVersionUuid ?? '')} onChange={(event) => set('steps', steps.map((item, position) => position === index ? { ...item, scenarioVersionUuid: event.target.value } : item))} /><button className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => set('steps', steps.filter((_, position) => position !== index))}><Trash2 size={15} /></button></div>)}</section></div>
  }

  if (type === 'PACKAGE') {
    const steps = Array.isArray(content.steps) ? content.steps.map(record) : []
    const replaceSteps = (next: Content[]) => set('steps', next)
    return <div className="structured-package-editor">
      <label><span>{t('firstStep')}</span><select value={String(content.firstStepId ?? '')} onChange={(event) => set('firstStepId', event.target.value)}>{steps.map((step, index) => <option key={index} value={String(step.id ?? '')}>{String(step.id ?? '')}</option>)}</select></label>
      <header><div><h3>{t('packageSteps')}</h3><p>{t('packageStepsHint')}</p></div><button className="definition-button definition-button--quiet" type="button" onClick={() => { const id = `STEP_${steps.length + 1}`; onChange({ ...content, steps: [...steps, { id, type: 'PROCEDURE' }], firstStepId: content.firstStepId || id }) }}><Plus size={15} />{t('addStep')}</button></header>
      <div className="structured-package-list">{steps.map((step, index) => <article key={index}>
        <span className="procedure-step-number">{index + 1}</span>
        <label><span>{t('stepId')}</span><input value={String(step.id ?? '')} onChange={(event) => replaceSteps(steps.map((item, position) => position === index ? { ...item, id: event.target.value } : item))} /></label>
        <label><span>{t('type')}</span><select value={String(step.type ?? 'PROCEDURE')} onChange={(event) => replaceSteps(steps.map((item, position) => position === index ? { ...item, type: event.target.value } : item))}><option>MAPPING</option><option>PROCEDURE</option><option>PACKAGE</option><option>SCENARIO</option></select></label>
        <button className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => replaceSteps(steps.filter((_, position) => position !== index))}><Trash2 size={15} /></button>
      </article>)}</div>
    </div>
  }
  return <div className="definition-state"><p>{t('advancedEditorRequired')}</p></div>
}
