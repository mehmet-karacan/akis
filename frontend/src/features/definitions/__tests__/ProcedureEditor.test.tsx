import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../../../core/i18n'
import { DEFAULT_PROCEDURE, isProcedureContent } from '../defaults'
import { applyAutomaticRowHandoffs, pageProcedureTasks, ProcedureEditor } from '../ProcedureEditor'
import type { ProcedureContent } from '../types'

function Harness() {
  const [content, setContent] = useState<ProcedureContent>(DEFAULT_PROCEDURE)
  return <ProcedureEditor projectUuid="project" value={content} onChange={setContent} />
}

describe('ProcedureEditor', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('adds, removes, and reorders an unrestricted ordered step list', () => {
    const { container } = render(<Harness />)

    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'Add Target Step' }))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(3)
    expect(screen.getByDisplayValue('STEP_3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Move Up: Target command' }))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    fireEvent.click(screen.getByRole('button', { name: 'Remove: Target command' }))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
  })

  it('keeps row handoff references valid when source step IDs change', async () => {
    render(<Harness />)
    fireEvent.change(screen.getByDisplayValue('READ_SOURCE'), { target: { value: 'READ_SKY' } })
    await waitFor(() => expect(screen.getByDisplayValue('READ_SKY')).toBeInTheDocument())
  })

  it('moves an automatically paired row producer and consumer together', () => {
    const { container } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Add Target Step' }))
    fireEvent.click(screen.getByRole('button', { name: 'Move Down: Read source rows' }))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    expect(container.querySelectorAll('.procedure-task')[1]).toHaveTextContent('Read source rows')
    expect(container.querySelectorAll('.procedure-task')[2]).toHaveTextContent('Insert target rows')
  })

  it('keeps row transfer and timeout engine details out of the step form', () => {
    render(<Harness />)
    expect(screen.queryByLabelText('Expose SELECT rows to a later step')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Maximum rows')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Consume rows from an earlier SELECT')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Timeout (seconds)')).not.toBeInTheDocument()
  })

  it('derives adjacent source-to-target row transfer with platform limits', () => {
    const tasks = applyAutomaticRowHandoffs(DEFAULT_PROCEDURE.tasks, 750)
    expect(tasks[0]?.output).toEqual({ kind: 'ROWSET', maxRows: 750 })
    expect(tasks[1]?.input).toEqual({ fromTask: 'READ_SOURCE', mode: 'BATCH', batchSize: 250 })
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
