import { Plus, Trash2 } from 'lucide-react'
import { useDefinitionsI18n } from './i18n'
import type { DefinitionType } from './types'

interface Props { type: DefinitionType; value: unknown; onChange: (value: unknown) => void }
type Content = Record<string, unknown>
const record = (value: unknown): Content => value && typeof value === 'object' && !Array.isArray(value) ? value as Content : {}

export function StructuredDraftEditor({ type, value, onChange }: Props) {
  const { t } = useDefinitionsI18n()
  const content = record(value)
  const set = (key: string, next: unknown) => onChange({ ...content, [key]: next })

  if (type === 'VARIABLE') return <div className="structured-draft-form">
    <label><span>{t('dataType')}</span><select value={String(content.dataType ?? 'STRING')} onChange={(event) => set('dataType', event.target.value)}><option>STRING</option><option>NUMBER</option><option>DATE</option><option>BOOLEAN</option></select></label>
    <label><span>{t('scope')}</span><select value={String(content.scope ?? 'PROJECT')} onChange={(event) => set('scope', event.target.value)}><option>PROJECT</option><option>PACKAGE</option></select></label>
    <label><span>{t('historyMode')}</span><select value={String(content.historyMode ?? 'LATEST')} onChange={(event) => set('historyMode', event.target.value)}><option>LATEST</option><option>HISTORY</option></select></label>
    <label><span>{t('valueSource')}</span><select value={String(content.valueSource ?? 'INPUT')} onChange={(event) => set('valueSource', event.target.value)}><option>INPUT</option><option>SQL</option><option>DEFAULT</option></select></label>
    {content.valueSource === 'DEFAULT' && <label className="structured-draft-wide"><span>{t('defaultValue')}</span><input value={String(content.defaultValue ?? '')} onChange={(event) => set('defaultValue', event.target.value)} /></label>}
    {content.valueSource === 'SQL' && <label className="structured-draft-wide"><span>{t('sqlCommand')}</span><textarea spellCheck={false} value={String(content.query ?? '')} onChange={(event) => set('query', event.target.value)} /></label>}
  </div>

  if (type === 'SEQUENCE') return <div className="structured-draft-form">
    <label><span>{t('implementation')}</span><select value={String(content.implementation ?? 'REPOSITORY')} onChange={(event) => set('implementation', event.target.value)}><option>REPOSITORY</option><option>NATIVE</option></select></label>
    <label><span>{t('startValue')}</span><input type="number" value={Number(content.start ?? 1)} onChange={(event) => set('start', Number(event.target.value))} /></label>
    <label><span>{t('incrementValue')}</span><input type="number" value={Number(content.increment ?? 1)} onChange={(event) => set('increment', Number(event.target.value))} /></label>
    <label className="procedure-checkbox"><input type="checkbox" checked={content.cycle === true} onChange={(event) => set('cycle', event.target.checked)} /><span>{t('cycle')}</span></label>
  </div>

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
