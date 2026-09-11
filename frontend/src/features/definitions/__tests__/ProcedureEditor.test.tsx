import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../../../core/i18n'
import { DEFAULT_PROCEDURE, isProcedureContent, supportsVisualEditor } from '../defaults'
import { ProcedureEditor } from '../ProcedureEditor'
import type { ProcedureContent } from '../types'

function Harness() {
  const [content, setContent] = useState<ProcedureContent>(DEFAULT_PROCEDURE)
  return <ProcedureEditor value={content} onChange={setContent} />
}

describe('ProcedureEditor', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('adds, removes, and reorders an unrestricted ordered step list', () => {
    const { container } = render(<Harness />)

    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'Add target step' }))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(3)
    expect(screen.getByDisplayValue('STEP_3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Move up: Target command' }))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    fireEvent.click(screen.getByRole('button', { name: 'Remove: Target command' }))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
  })

  it('keeps row handoff references valid when source step IDs change', () => {
    render(<Harness />)
    fireEvent.change(screen.getByDisplayValue('READ_SOURCE'), { target: { value: 'READ_SKY' } })
    fireEvent.click(document.querySelectorAll<HTMLButtonElement>('.procedure-task-select')[1]!)
    expect(screen.getByDisplayValue('READ_SKY')).toBeInTheDocument()
  })

  it('moves a row producer and its consumer together without changing the handoff', () => {
    const { container } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Add target step' }))
    fireEvent.click(screen.getByRole('button', { name: 'Move down: Read source rows' }))
    expect(container.querySelectorAll('.procedure-task')[0]).toHaveTextContent('Target command')
    fireEvent.click(document.querySelectorAll<HTMLButtonElement>('.procedure-task-select')[2]!)
    expect(screen.getByLabelText('Consume rows from an earlier SELECT')).toBeChecked()
    expect(screen.getByDisplayValue('READ_SOURCE')).toBeInTheDocument()
  })

  it('uses the current runtime row and timeout limits', () => {
    render(<Harness />)
    expect(screen.getByLabelText('Maximum rows')).toHaveValue(1000)
    expect(screen.getByLabelText('Maximum rows')).toHaveAttribute('max', '1000')
    expect(screen.getByLabelText('Timeout (seconds)')).toHaveAttribute('max', '300')
  })

  it('rejects malformed task arrays before the visual editor renders them', () => {
    expect(isProcedureContent({ tasks: [null] })).toBe(false)
    expect(isProcedureContent({ tasks: [{ id: 'BROKEN' }] })).toBe(false)
    expect(isProcedureContent(DEFAULT_PROCEDURE)).toBe(true)
  })

  it('keeps legacy procedure drafts JSON-only until an explicit v2 upgrade', () => {
    expect(supportsVisualEditor('PROCEDURE', 1)).toBe(false)
    expect(supportsVisualEditor('PROCEDURE', 2)).toBe(true)
    expect(supportsVisualEditor('MAPPING', 1)).toBe(true)
  })
})
