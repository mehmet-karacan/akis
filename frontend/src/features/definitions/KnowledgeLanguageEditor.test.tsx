import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { KnowledgeModuleEditor } from './KnowledgeModuleEditor'
import { createKnowledgeModule } from './knowledgeModuleTemplates'
import { knowledgeOptionsForContent } from './knowledgeModuleOptions'
import { apiRequest } from '../../core/api/client'

vi.mock('../../core/api/client', () => ({ apiRequest: vi.fn(), jsonBody: (body: unknown) => ({ body: JSON.stringify(body) }) }))

const changed = vi.fn()
function Harness() {
  const [value, setValue] = useState<Record<string, unknown>>(createKnowledgeModule())
  return <KnowledgeModuleEditor projectUuid="project" value={value} onChange={next => { changed(next); setValue(next as Record<string, unknown>) }} />
}
beforeEach(async () => { vi.clearAllMocks(); await i18n.changeLanguage('en') })
it('shows the declared run condition without claiming the language check executed the module', async () => {
  vi.mocked(apiRequest).mockResolvedValueOnce({ valid: true, runnable: false, line: 0, message: 'Syntax valid; not executed', program: {
    steps: [{ id: 'QUALITY', site: 'STAGING', operation: 'CHECK_NOT_NULL', slot: 'WORK_SOURCE_1', line: 4 },
      { id: 'OTHER', site: 'STAGING', operation: 'CHECK_UNIQUE', slot: 'WORK_SOURCE_1', line: 5 }],
    conditions: { QUALITY: 'CHECK_ROWS' },
  } })
  render(<Harness />)
  fireEvent.click(screen.getByRole('button', { name: 'Validate Language' }))
  expect(await screen.findByText('CHECK_ROWS = true')).toBeVisible()
  expect(screen.getByText('Always')).toBeVisible()
  expect(screen.getByText('Syntax valid; not executed')).toBeVisible()
  expect(screen.getByText(/TRUNCATE requires both/)).toBeVisible()
})
it('opens the executable editor immediately and requires confirmation before replacing a template', async () => {
  render(<Harness />)
  const source = screen.getByLabelText('KM Language Source')
  const initial = (source as HTMLTextAreaElement).value
  expect(screen.queryByText('Use AKIŞ KM Language')).not.toBeInTheDocument()
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Module Type and Template' }))
  fireEvent.click(await screen.findByText('LKM · Loading'))
  // Ant's test-only IDs collide in jsdom; named dialog accessibility is checked in the browser.
  let dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('Replace Module Template')).toBeInTheDocument()
  fireEvent.click(within(dialog).getAllByRole('button', { name: 'Cancel' }).at(-1)!)
  expect(source).toHaveValue(initial)
  expect(changed).not.toHaveBeenCalled()
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Module Type and Template' }))
  fireEvent.click(await screen.findByText('CKM · Data Check'))
  dialog = screen.getByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button', { name: 'Apply Template' }))
  expect(source).toHaveValue(createKnowledgeModule('CKM').source)
})
it('preserves names, descriptions, enum choices and quoted defaults without losing edited option rows', () => {
  render(<Harness />)
  fireEvent.change(screen.getAllByLabelText('Display Name')[0]!, { target: { value: 'Write Strategy' } })
  fireEvent.change(screen.getAllByLabelText('Description')[0]!, { target: { value: 'Choose how target rows are written' } })
  fireEvent.change(screen.getByLabelText('Allowed Values'), { target: { value: 'APPEND,MERGE,ATOMIC_DELETE_INSERT' } })
  fireEvent.change(screen.getAllByLabelText('Default')[2]!, { target: { value: 'FULL(T) PARALLEL(4)' } })
  expect(screen.getAllByLabelText('Display Name')[0]).toHaveValue('Write Strategy')
  expect(screen.getAllByLabelText('Description')[0]).toHaveValue('Choose how target rows are written')
  expect((screen.getByLabelText('KM Language Source') as HTMLTextAreaElement).value).toContain('"FULL(T) PARALLEL(4)"')
  const latest = changed.mock.calls.at(-1)![0] as Record<string, unknown>
  expect(knowledgeOptionsForContent(latest)[0]).toMatchObject({ label: 'Write Strategy', description: 'Choose how target rows are written', values: ['APPEND', 'MERGE', 'ATOMIC_DELETE_INSERT'] })
  const key = screen.getAllByLabelText('Key')[3]!
  key.focus()
  fireEvent.change(key, { target: { value: '' } })
  expect(key).toHaveFocus()
  expect(screen.getAllByLabelText('Key')).toHaveLength(4)
  fireEvent.change(key, { target: { value: 'ORACLE_HINT' } })
  expect(key).toHaveValue('ORACLE_HINT')
  const finalSource = (screen.getByLabelText('KM Language Source') as HTMLTextAreaElement).value
  expect(finalSource.match(/^SECENEK /gm)).toHaveLength(4)
  expect(finalSource).not.toContain('SECENEK  ')
})
