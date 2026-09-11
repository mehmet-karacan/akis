import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
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
  const [selectedTaskId, setSelectedTaskId] = useState(value.tasks[0]?.id ?? '')
  useEffect(() => {
    if (!value.tasks.some((task) => task.id === selectedTaskId)) setSelectedTaskId(value.tasks[0]?.id ?? '')
  }, [selectedTaskId, value.tasks])
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
  const add = (role: ProcedureConnectionRole) => {
    const task = nextTask(role, value.tasks)
    replaceTasks([...value.tasks, task])
    setSelectedTaskId(task.id)
  }
  const remove = (index: number) => {
    const tasks = value.tasks.filter((_, position) => position !== index)
    replaceTasks(tasks)
    if (value.tasks[index]?.id === selectedTaskId) setSelectedTaskId(tasks[Math.min(index, tasks.length - 1)]?.id ?? '')
  }

  const selectedIndex = value.tasks.findIndex((task) => task.id === selectedTaskId)
  const selectedTask = value.tasks[selectedIndex]
  const rowsetTasks = selectedIndex < 0 ? [] : value.tasks.slice(0, selectedIndex).filter((candidate) => candidate.output?.kind === 'ROWSET')
  const highRisk = selectedTask?.riskClass === 'DDL' || selectedTask?.riskClass === 'DESTRUCTIVE'

  return (
    <div className="procedure-editor">
      <header className="procedure-editor-heading">
        <div><h3>{t('procedureSteps')}</h3><p>{t('procedureHint')}</p></div>
        <div className="mapping-inline-actions">
          <button className="definition-button definition-button--quiet" type="button" onClick={() => add('SOURCE')}><Plus size={15} />{t('addSourceStep')}</button>
          <button className="definition-button definition-button--quiet" type="button" onClick={() => add('TARGET')}><Plus size={15} />{t('addTargetStep')}</button>
        </div>
      </header>
      <p className="procedure-runtime-profile">{t('procedureRuntimeProfile')}</p>
      {reorderError && <p className="procedure-reorder-error" role="alert">{reorderError}</p>}
      <div className="procedure-workbench">
        <div className="procedure-task-list" role="list" aria-label={t('procedureSteps')}>
          {value.tasks.map((task, index) => <article className={`procedure-task ${task.id === selectedTaskId ? 'is-selected' : ''}`} key={`${task.id}-${index}`}>
            <button className="procedure-task-select" type="button" onClick={() => setSelectedTaskId(task.id)} aria-pressed={task.id === selectedTaskId}>
                <span className="procedure-step-number">{index + 1}</span>
                <div><strong>{task.name || task.id}</strong><small>{task.connectionRole} · {task.type} · {task.riskClass}</small></div>
            </button>
            <div className="procedure-task-actions">
              <button className="definition-icon-button" type="button" aria-label={`${t('moveUp')}: ${task.name || task.id}`} disabled={index === 0} onClick={() => move(index, -1)}><ArrowUp size={15} /></button>
              <button className="definition-icon-button" type="button" aria-label={`${t('moveDown')}: ${task.name || task.id}`} disabled={index === value.tasks.length - 1} onClick={() => move(index, 1)}><ArrowDown size={15} /></button>
              <button className="definition-icon-button" type="button" aria-label={`${t('remove')}: ${task.name || task.id}`} onClick={() => remove(index)}><Trash2 size={15} /></button>
            </div>
          </article>)}
        </div>
        {selectedTask ? <section className="procedure-task-editor" aria-label={`${t('stepEditor')}: ${selectedTask.name || selectedTask.id}`}>
          <header><div><p className="eyebrow">{t('selectedStep')}</p><h3>{selectedTask.name || selectedTask.id}</h3></div><span>{selectedIndex + 1} / {value.tasks.length}</span></header>
              <div className="procedure-task-grid">
                <label><span>{t('stepId')}</span><input value={selectedTask.id} onChange={(event) => { setSelectedTaskId(event.target.value); update(selectedIndex, { ...selectedTask, id: event.target.value }) }} /></label>
                <label><span>{t('stepName')}</span><input value={selectedTask.name ?? ''} onChange={(event) => update(selectedIndex, { ...selectedTask, name: event.target.value })} /></label>
                <label><span>{t('taskType')}</span><select value={selectedTask.type} onChange={(event) => update(selectedIndex, { ...selectedTask, type: event.target.value as ProcedureTaskType })}><option>SQL</option><option>PLSQL</option><option>STORED_PROCEDURE</option></select></label>
                <label><span>{t('connectionRole')}</span><select value={selectedTask.connectionRole} onChange={(event) => update(selectedIndex, { ...selectedTask, connectionRole: event.target.value as ProcedureConnectionRole })}><option>SOURCE</option><option>TARGET</option></select></label>
                <label><span>{t('riskClass')}</span><select value={selectedTask.riskClass} onChange={(event) => update(selectedIndex, { ...selectedTask, riskClass: event.target.value as ProcedureRiskClass, requiresApproval: undefined })}><option>READ_ONLY</option><option>DML</option><option>DDL</option><option>DESTRUCTIVE</option></select></label>
                <label><span>{t('onError')}</span><select value={selectedTask.onError ?? 'STOP'} onChange={(event) => update(selectedIndex, { ...selectedTask, onError: event.target.value as 'STOP' | 'CONTINUE' })}><option>STOP</option><option>CONTINUE</option></select></label>
                <label><span>{t('timeoutSeconds')}</span><input type="number" min="1" max="300" value={selectedTask.timeoutSeconds ?? 300} onChange={(event) => update(selectedIndex, { ...selectedTask, timeoutSeconds: Number(event.target.value) })} /></label>
                {highRisk && <label className="procedure-checkbox"><input type="checkbox" checked={selectedTask.requiresApproval === true} onChange={(event) => update(selectedIndex, { ...selectedTask, requiresApproval: event.target.checked })} /><span>{t('requiresApproval')}</span></label>}
              </div>
              <label className="procedure-command"><span>{t('sqlCommand')}</span><textarea spellCheck={false} value={selectedTask.command} onChange={(event) => update(selectedIndex, { ...selectedTask, command: event.target.value })} /></label>
              <div className="procedure-flow-options">
                <label className="procedure-checkbox"><input type="checkbox" checked={selectedTask.output?.kind === 'ROWSET'} disabled={selectedTask.connectionRole !== 'SOURCE' || selectedTask.type !== 'SQL' || selectedTask.riskClass !== 'READ_ONLY'} onChange={(event) => update(selectedIndex, { ...selectedTask, output: event.target.checked ? { kind: 'ROWSET', maxRows: 1000 } : undefined })} /><span>{t('captureRows')}</span></label>
                {selectedTask.output && <label><span>{t('maxRows')}</span><input type="number" min="1" max="1000" value={selectedTask.output.maxRows} onChange={(event) => update(selectedIndex, { ...selectedTask, output: { kind: 'ROWSET', maxRows: Number(event.target.value) } })} /></label>}
                <label className="procedure-checkbox"><input type="checkbox" checked={!!selectedTask.input} disabled={selectedTask.connectionRole !== 'TARGET' || selectedTask.type !== 'SQL' || selectedTask.riskClass !== 'DML' || rowsetTasks.length === 0} onChange={(event) => update(selectedIndex, { ...selectedTask, input: event.target.checked ? { fromTask: rowsetTasks[0]!.id, mode: 'BATCH', batchSize: 250 } : undefined })} /><span>{t('consumeRows')}</span></label>
                {selectedTask.input && <><label><span>{t('fromTask')}</span><select value={selectedTask.input.fromTask} onChange={(event) => update(selectedIndex, { ...selectedTask, input: { ...selectedTask.input!, fromTask: event.target.value } })}>{rowsetTasks.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.id}</option>)}</select></label><label><span>{t('batchSize')}</span><input type="number" min="1" max="1000" value={selectedTask.input.batchSize} onChange={(event) => update(selectedIndex, { ...selectedTask, input: { ...selectedTask.input!, batchSize: Number(event.target.value) } })} /></label></>}
              </div>
        </section> : <p className="definition-state">{t('noProcedureSteps')}</p>}
      </div>
    </div>
  )
}
