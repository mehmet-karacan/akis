import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useDefinitionsI18n } from './i18n'
import type {
  ProcedureConnectionRole,
  ProcedureContent,
  ProcedureRiskClass,
  ProcedureTask,
  ProcedureTaskType,
} from './types'

interface ProcedureEditorProps {
  value: ProcedureContent
  onChange: (value: ProcedureContent) => void
}

function nextTask(role: ProcedureConnectionRole, tasks: ProcedureTask[]): ProcedureTask {
  let number = tasks.length + 1
  let id = `STEP_${number}`
  while (tasks.some((task) => task.id === id)) id = `STEP_${++number}`
  return {
    id,
    name: role === 'SOURCE' ? 'Read source' : 'Target command',
    type: 'SQL',
    connectionRole: role,
    riskClass: role === 'SOURCE' ? 'READ_ONLY' : 'DML',
    command: role === 'SOURCE' ? 'SELECT * FROM SOURCE_TABLE' : 'INSERT INTO TARGET_TABLE (ID) VALUES (:ID)',
    onError: 'STOP',
    timeoutSeconds: 300,
  }
}

function normalizeTasks(tasks: ProcedureTask[]): ProcedureTask[] {
  const availableRowsets = new Set<string>()
  return tasks.map((task) => {
    const canOutput = task.connectionRole === 'SOURCE' && task.type === 'SQL' && task.riskClass === 'READ_ONLY'
    const canConsume = task.connectionRole === 'TARGET' && task.type === 'SQL' && task.riskClass === 'DML'
    const normalized = {
      ...task,
      output: canOutput ? task.output : undefined,
      input: canConsume && task.input && availableRowsets.has(task.input.fromTask) ? task.input : undefined,
    }
    if (normalized.output?.kind === 'ROWSET') availableRowsets.add(normalized.id)
    return normalized
  })
}

export function ProcedureEditor({ value, onChange }: ProcedureEditorProps) {
  const { t } = useDefinitionsI18n()
  const [reorderError, setReorderError] = useState('')
  const replaceTasks = (tasks: ProcedureTask[]) => onChange({ ...value, tasks: normalizeTasks(tasks) })
  const update = (index: number, task: ProcedureTask) => {
    const previousId = value.tasks[index]?.id
    const tasks = value.tasks.map((current, position) => {
      if (position === index) return task
      if (previousId !== task.id && current.input?.fromTask === previousId) {
        return { ...current, input: { ...current.input!, fromTask: task.id } }
      }
      return current
    })
    replaceTasks(tasks)
  }
  const move = (index: number, offset: number) => {
    const tasks = [...value.tasks]
    const selected = tasks[index]
    if (!selected) return
    const units: ProcedureTask[][] = []
    for (let position = 0; position < tasks.length; position += 1) {
      const current = tasks[position]!
      const next = tasks[position + 1]
      if (current.output?.kind === 'ROWSET' && next?.input?.fromTask === current.id) {
        units.push([current, next])
        position += 1
      } else {
        units.push([current])
      }
    }
    const unitIndex = units.findIndex((unit) => unit.includes(selected))
    const targetUnitIndex = unitIndex + offset
    if (unitIndex < 0 || targetUnitIndex < 0 || targetUnitIndex >= units.length) {
      setReorderError(units[unitIndex]?.length === 2 ? t('procedurePairMoveBlocked') : '')
      return
    }
    ;[units[unitIndex], units[targetUnitIndex]] = [units[targetUnitIndex]!, units[unitIndex]!]
    setReorderError('')
    replaceTasks(units.flat())
  }

  return (
    <div className="procedure-editor">
      <header className="procedure-editor-heading">
        <div><h3>{t('procedureSteps')}</h3><p>{t('procedureHint')}</p></div>
        <div className="mapping-inline-actions">
          <button className="definition-button definition-button--quiet" type="button" onClick={() => replaceTasks([...value.tasks, nextTask('SOURCE', value.tasks)])}><Plus size={15} />{t('addSourceStep')}</button>
          <button className="definition-button definition-button--quiet" type="button" onClick={() => replaceTasks([...value.tasks, nextTask('TARGET', value.tasks)])}><Plus size={15} />{t('addTargetStep')}</button>
        </div>
      </header>
      <p className="procedure-runtime-profile">{t('procedureRuntimeProfile')}</p>
      {reorderError && <p className="procedure-reorder-error" role="alert">{reorderError}</p>}
      <div className="procedure-task-list">
        {value.tasks.map((task, index) => {
          const rowsetTasks = value.tasks.slice(0, index).filter((candidate) => candidate.output?.kind === 'ROWSET')
          const highRisk = task.riskClass === 'DDL' || task.riskClass === 'DESTRUCTIVE'
          return (
            <article className="procedure-task" key={`${task.id}-${index}`}>
              <header>
                <span className="procedure-step-number">{index + 1}</span>
                <div><strong>{task.name || task.id}</strong><small>{task.connectionRole} · {task.type} · {task.riskClass}</small></div>
                <div className="procedure-task-actions">
                  <button className="definition-icon-button" type="button" aria-label={t('moveUp')} disabled={index === 0} onClick={() => move(index, -1)}><ArrowUp size={15} /></button>
                  <button className="definition-icon-button" type="button" aria-label={t('moveDown')} disabled={index === value.tasks.length - 1} onClick={() => move(index, 1)}><ArrowDown size={15} /></button>
                  <button className="definition-icon-button" type="button" aria-label={t('remove')} onClick={() => replaceTasks(value.tasks.filter((_, position) => position !== index))}><Trash2 size={15} /></button>
                </div>
              </header>
              <div className="procedure-task-grid">
                <label><span>{t('stepId')}</span><input value={task.id} onChange={(event) => update(index, { ...task, id: event.target.value })} /></label>
                <label><span>{t('stepName')}</span><input value={task.name ?? ''} onChange={(event) => update(index, { ...task, name: event.target.value })} /></label>
                <label><span>{t('taskType')}</span><select value={task.type} onChange={(event) => update(index, { ...task, type: event.target.value as ProcedureTaskType })}><option>SQL</option><option>PLSQL</option><option>STORED_PROCEDURE</option></select></label>
                <label><span>{t('connectionRole')}</span><select value={task.connectionRole} onChange={(event) => update(index, { ...task, connectionRole: event.target.value as ProcedureConnectionRole })}><option>SOURCE</option><option>TARGET</option></select></label>
                <label><span>{t('riskClass')}</span><select value={task.riskClass} onChange={(event) => update(index, { ...task, riskClass: event.target.value as ProcedureRiskClass, requiresApproval: undefined })}><option>READ_ONLY</option><option>DML</option><option>DDL</option><option>DESTRUCTIVE</option></select></label>
                <label><span>{t('onError')}</span><select value={task.onError ?? 'STOP'} onChange={(event) => update(index, { ...task, onError: event.target.value as 'STOP' | 'CONTINUE' })}><option>STOP</option><option>CONTINUE</option></select></label>
                <label><span>{t('timeoutSeconds')}</span><input type="number" min="1" max="300" value={task.timeoutSeconds ?? 300} onChange={(event) => update(index, { ...task, timeoutSeconds: Number(event.target.value) })} /></label>
                {highRisk && <label className="procedure-checkbox"><input type="checkbox" checked={task.requiresApproval === true} onChange={(event) => update(index, { ...task, requiresApproval: event.target.checked })} /><span>{t('requiresApproval')}</span></label>}
              </div>
              <label className="procedure-command"><span>{t('sqlCommand')}</span><textarea spellCheck={false} value={task.command} onChange={(event) => update(index, { ...task, command: event.target.value })} /></label>
              <div className="procedure-flow-options">
                <label className="procedure-checkbox"><input type="checkbox" checked={task.output?.kind === 'ROWSET'} disabled={task.connectionRole !== 'SOURCE' || task.type !== 'SQL' || task.riskClass !== 'READ_ONLY'} onChange={(event) => update(index, { ...task, output: event.target.checked ? { kind: 'ROWSET', maxRows: 1000 } : undefined })} /><span>{t('captureRows')}</span></label>
                {task.output && <label><span>{t('maxRows')}</span><input type="number" min="1" max="1000" value={task.output.maxRows} onChange={(event) => update(index, { ...task, output: { kind: 'ROWSET', maxRows: Number(event.target.value) } })} /></label>}
                <label className="procedure-checkbox"><input type="checkbox" checked={!!task.input} disabled={task.connectionRole !== 'TARGET' || task.type !== 'SQL' || task.riskClass !== 'DML' || rowsetTasks.length === 0} onChange={(event) => update(index, { ...task, input: event.target.checked ? { fromTask: rowsetTasks[0]!.id, mode: 'BATCH', batchSize: 250 } : undefined })} /><span>{t('consumeRows')}</span></label>
                {task.input && <><label><span>{t('fromTask')}</span><select value={task.input.fromTask} onChange={(event) => update(index, { ...task, input: { ...task.input!, fromTask: event.target.value } })}>{rowsetTasks.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}</select></label><label><span>{t('batchSize')}</span><input type="number" min="1" max="1000" value={task.input.batchSize} onChange={(event) => update(index, { ...task, input: { ...task.input!, batchSize: Number(event.target.value) } })} /></label></>}
              </div>
            </article>
          )
        })}
      </div>
    </div>
  )
}
