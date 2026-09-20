import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import { DEFAULT_PROCEDURE, isProcedureContent } from '../defaults'
import { applyAutomaticRowHandoffs, groupProcedureTasks, inferProcedureTaskMetadata, isProcedureSideConfigured, pageProcedureTasks, ProcedureEditor } from '../ProcedureEditor'
import { inferProcedureLogCounter } from '../procedureCatalog'
import type { ProcedureContent } from '../types'
import { definitionsApi } from '../api'
import { topologyApi } from '../../topology/api'

async function renderHarness() {
  return await act(async () => render(<Harness />))
}

function Harness() {
  const [content, setContent] = useState<ProcedureContent>(DEFAULT_PROCEDURE)
  return <ProcedureEditor projectUuid="project" value={content} onChange={setContent} />
}

function openTasks() {
  expect(screen.getByRole('heading', { name: 'Procedure steps' })).toBeInTheDocument()
}

describe('ProcedureEditor', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([])
    vi.spyOn(topologyApi, 'listEnvironments').mockResolvedValue([])
    vi.spyOn(definitionsApi, 'listDefinitions').mockResolvedValue([])
    await i18n.changeLanguage('en')
  })

  it('adds, removes, and reorders an unrestricted ordered step list', async () => {
    const { container } = await renderHarness()
    openTasks()

    expect(container.querySelectorAll('.procedure-task')).toHaveLength(1)
    fireEvent.click(screen.getByText('Add Step', { exact: true }).closest('button')!)
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
    expect(screen.getByDisplayValue('Target command')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Move Up: Target command'))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    fireEvent.click(screen.getByLabelText('Remove: Target command'))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(1)
  })

  it('keeps internal step IDs out of the form and edits the conceptual step name', async () => {
    await renderHarness()
    openTasks()
    expect(screen.queryByLabelText('Step ID')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Step Name'), { target: { value: 'Load Hakedis' } })
    await waitFor(() => expect(screen.getByDisplayValue('Load Hakedis')).toBeInTheDocument())
  })

  it('moves an automatically paired row producer and consumer together', async () => {
    const { container } = await renderHarness()
    openTasks()
    fireEvent.click(screen.getByText('Add Step', { exact: true }).closest('button')!)
    fireEvent.click(screen.getByLabelText('Move Down: Insert target rows'))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    expect(container.querySelectorAll('.procedure-task')[1]).toHaveTextContent('Insert target rows')
  })

  it('keeps row transfer and timeout engine details out of the step form', async () => {
    await renderHarness()
    openTasks()
    expect(screen.queryByLabelText('Expose SELECT rows to a later step')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Maximum rows')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Consume rows from an earlier SELECT')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Timeout (seconds)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Task type')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Connection role')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Risk class')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('On error')).not.toBeInTheDocument()
  })

  it('derives adjacent source-to-target row transfer with platform limits', () => {
    const tasks = applyAutomaticRowHandoffs(DEFAULT_PROCEDURE.tasks, 750)
    expect(tasks[0]?.output).toEqual({ kind: 'ROWSET', maxRows: 750 })
    expect(tasks[1]?.input).toEqual({ fromTask: 'READ_SOURCE', mode: 'BATCH', batchSize: 250 })
  })

  it('presents adjacent source and target tasks as one conceptual step', () => {
    const units = groupProcedureTasks(DEFAULT_PROCEDURE.tasks.map((task) => ({ ...task, input: undefined, output: undefined })))
    expect(units).toHaveLength(1)
    expect(units[0]?.source?.connectionRole).toBe('SOURCE')
    expect(units[0]?.target?.connectionRole).toBe('TARGET')
  })

  it('infers hidden execution metadata from free-form SQL', () => {
    const target = DEFAULT_PROCEDURE.tasks[1]!
    expect(inferProcedureTaskMetadata(target, 'truncate table INNOVA_ODI.STG_X')).toEqual({ type: 'SQL', riskClass: 'DESTRUCTIVE', requiresApproval: true })
    expect(inferProcedureTaskMetadata(target, "begin dbms_stats.gather_table_stats('A', 'B'); end;")).toEqual({ type: 'PLSQL', riskClass: 'DESTRUCTIVE', requiresApproval: true })
    expect(inferProcedureTaskMetadata(target, 'insert into T values (:ID)')).toEqual({ type: 'SQL', riskClass: 'DML', requiresApproval: undefined })
  })

  it('uses the shared log counter catalog for common SQL operations', () => {
    expect(inferProcedureLogCounter('insert into T values (1)')).toBe('INSERT')
    expect(inferProcedureLogCounter('update T set C = 1')).toBe('UPDATE')
    expect(inferProcedureLogCounter('delete from T')).toBe('DELETE')
    expect(inferProcedureLogCounter('truncate table T')).toBe('NONE')
    expect(inferProcedureLogCounter("begin dbms_stats.gather_table_stats('A', 'B'); end;")).toBe('NONE')
    expect(inferProcedureLogCounter('select * from T')).toBe('NONE')
  })

  it('renders added steps as full-width master rows above the selected detail', async () => {
    const { container } = await renderHarness()
    openTasks()
    fireEvent.click(screen.getByText('Add Step', { exact: true }).closest('button')!)
    const rows = container.querySelectorAll('.procedure-task-table tbody > .procedure-task')
    expect(rows).toHaveLength(2)
    expect(rows[0]?.parentElement).toBe(rows[1]?.parentElement)
    expect(screen.getByLabelText('Log Counter')).toBeInTheDocument()
  })

  it('keeps the selected command tab while moving between steps', async () => {
    const { container } = await renderHarness()
    openTasks()
    fireEvent.click(screen.getByText('Add Step', { exact: true }).closest('button')!)
    fireEvent.click(screen.getByRole('tab', { name: 'Target Command' }))
    fireEvent.click(container.querySelectorAll<HTMLButtonElement>('.procedure-task-select')[0]!)
    expect(screen.getByRole('tab', { name: 'Target Command' })).toHaveAttribute('aria-selected', 'true')
  })

  it('opens SQL through a dedicated editor instead of a variable dropdown', async () => {
    await renderHarness()
    fireEvent.click(screen.getByRole('tab', { name: 'Target Command' }))
    expect(screen.getByRole('button', { name: 'Edit SQL' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Defined Variables' })).not.toBeInTheDocument()
  })

  it('marks a command configured only when its execution context is complete', () => {
    const task = DEFAULT_PROCEDURE.tasks[1]!
    expect(isProcedureSideConfigured(task)).toBe(false)
    expect(isProcedureSideConfigured({ ...task, logicalSchemaUuid: 'logical', environmentUuid: 'environment' })).toBe(true)
  })


  it('rejects malformed task arrays before the visual editor renders them', () => {
    expect(isProcedureContent({ tasks: [null] })).toBe(false)
    expect(isProcedureContent({ tasks: [{ id: 'BROKEN' }] })).toBe(false)
    expect(isProcedureContent(DEFAULT_PROCEDURE)).toBe(true)
  })

  it('windows a 10,000-step fixture without cutting off navigation', () => {
    const tasks = Array.from({ length: 10_000 }, (_, index) => ({ ...DEFAULT_PROCEDURE.tasks[0]!, id: `STEP_${index + 1}`, name: `Step ${index + 1}` }))
    const last = pageProcedureTasks(tasks, '', 99)
    expect(last.items).toHaveLength(100)
    expect(last.items[0]?.task.id).toBe('STEP_9901')
    expect(last.pages).toBe(100)
  })
})
