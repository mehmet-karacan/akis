import { fireEvent, render, screen, within } from '@testing-library/react'
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

    const lastStep = container.querySelectorAll('.procedure-task')[2] as HTMLElement
    expect(within(lastStep).getByDisplayValue('STEP_3')).toBeInTheDocument()
    fireEvent.click(within(lastStep).getByRole('button', { name: 'Move up' }))
    expect(within(container.querySelectorAll('.procedure-task')[1] as HTMLElement).getByDisplayValue('STEP_3')).toBeInTheDocument()

    fireEvent.click(within(container.querySelectorAll('.procedure-task')[1] as HTMLElement).getByRole('button', { name: 'Remove' }))
    expect(container.querySelectorAll('.procedure-task')).toHaveLength(2)
  })

  it('keeps row handoff references valid when source step IDs change', () => {
    const { container } = render(<Harness />)
    const sourceStep = container.querySelectorAll('.procedure-task')[0] as HTMLElement

    fireEvent.change(within(sourceStep).getByDisplayValue('READ_SOURCE'), { target: { value: 'READ_SKY' } })

    const targetStep = container.querySelectorAll('.procedure-task')[1] as HTMLElement
    expect(within(targetStep).getByDisplayValue('READ_SKY')).toBeInTheDocument()
  })

  it('clears an invalid row handoff when its source is moved after the consumer', () => {
    const { container } = render(<Harness />)
    const sourceStep = container.querySelectorAll('.procedure-task')[0] as HTMLElement

    fireEvent.click(within(sourceStep).getByRole('button', { name: 'Move down' }))

    const formerConsumer = container.querySelectorAll('.procedure-task')[1] as HTMLElement
    expect(within(formerConsumer).getByLabelText('Consume rows from an earlier SELECT')).not.toBeChecked()
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
