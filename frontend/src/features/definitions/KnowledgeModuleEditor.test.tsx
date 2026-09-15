import { render, screen, fireEvent } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it } from 'vitest'
import i18n from '../../core/i18n'
import { KnowledgeModuleEditor } from './KnowledgeModuleEditor'

function Harness() {
  const [value, setValue] = useState<Record<string, unknown>>({ kmType: 'IKM', tasks: [], options: [] })
  return <KnowledgeModuleEditor value={value} onChange={(next) => setValue(next as Record<string, unknown>)} />
}
beforeEach(async () => { await i18n.changeLanguage('en') })
it('edits tasks in a vertical master/detail view and preserves commands when reordering', () => {
  render(<Harness />)
  fireEvent.click(screen.getByText('Add Task'))
  fireEvent.change(screen.getByLabelText('Command'), { target: { value: 'SELECT 1' } })
  fireEvent.click(screen.getByText('Add Task'))
  fireEvent.click(screen.getByLabelText('Move Up'))
  fireEvent.click(screen.getByRole('button', { name: 'Task 1' }))
  expect(screen.getByLabelText('Command')).toHaveValue('SELECT 1')
  fireEvent.click(screen.getByLabelText('Delete Task'))
  expect(screen.queryByRole('button', { name: 'Task 1' })).not.toBeInTheDocument()
})
it('offers supported metadata categories and editable options without claiming runtime support', async () => {
  render(<Harness />)
  fireEvent.mouseDown(screen.getAllByRole('combobox')[0]!)
  expect(await screen.findByRole('option', { name: /JKM/ })).toBeInTheDocument()
  fireEvent.keyDown(screen.getAllByRole('combobox')[0]!, { key: 'Escape' })
  expect(screen.getByRole('note')).toHaveTextContent('not yet supported')
  fireEvent.click(screen.getByRole('tab', { name: 'Options' }))
  fireEvent.click(screen.getByText('Add Option'))
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'COMMIT' } })
  expect(screen.getByLabelText('Name')).toHaveValue('COMMIT')
})
