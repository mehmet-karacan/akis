import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import { ProcedureSqlPanel } from '../ProcedureSqlPanel'
import { definitionsApi } from '../api'
import type { Definition } from '../types'
vi.mock('../../../core/ui/SqlEditor', () => ({ SqlEditor: ({ value, onChange }: { value: string; onChange(value: string): void }) => <textarea aria-label="SQL Editor" value={value} onChange={e => onChange(e.target.value)} /> }))
const variable: Definition = { uuid: 'v', code: 'RUN_DATE', name: 'Run Date', type: 'VARIABLE', status: 'AKTIF', folderUuid: null, description: null, version: 1 }
const draft = { uuid: 'd', schemaVersion: 1, version: 1, content: { dataType: 'DATE', valueSource: 'REFRESH_QUERY', query: 'SELECT SYSDATE - 1 FROM DUAL', logicalSchemaUuid: 'logical', historyMode: 'ALL' } }
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })
it('edits inline and preserves inline text when the optional panel is cancelled', async () => {
  vi.spyOn(definitionsApi, 'getDraft').mockResolvedValue(draft)
  const apply = vi.fn()
  render(<ProcedureSqlPanel projectUuid="p" role="TARGET" variables={[variable]} onApply={apply} />)
  fireEvent.change(screen.getByLabelText('SQL Editor'), { target: { value: 'SELECT @RUN_DATE FROM DUAL' } })
  fireEvent.click(screen.getByRole('button', { name: 'Edit SQL' }))
  fireEvent.change(screen.getByLabelText('SQL Editor'), { target: { value: 'SELECT 2 FROM DUAL' } })
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  await waitFor(() => expect(screen.getAllByLabelText('SQL Editor')).toHaveLength(1))
  expect(screen.getByLabelText('SQL Editor')).toHaveValue('SELECT @RUN_DATE FROM DUAL')
  fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
  await waitFor(() => expect(apply).toHaveBeenCalledWith('SELECT :RUN_DATE FROM DUAL', expect.any(Object)))
})
function setup() {
  const apply = vi.fn()
  const result = render(<ProcedureSqlPanel projectUuid="p" role="TARGET" variables={[variable]} onApply={apply} />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit SQL' }))
  fireEvent.change(screen.getByLabelText('SQL Editor'), { target: { value: 'INSERT INTO t VALUES (:ID, ${RUN_DATE})' } })
  return { apply, ...result }
}
it('applies typed project binding while preserving row binds', async () => {
  vi.spyOn(definitionsApi, 'getDraft').mockResolvedValue(draft)
  const { apply } = setup()
  fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
  await waitFor(() => expect(apply).toHaveBeenCalledWith('INSERT INTO t VALUES (:ID, :RUN_DATE)', { RUN_DATE: expect.objectContaining({ type: 'DATE', definitionUuid: 'v', logicalSchemaUuid: 'logical', historyMode: 'ALL' }) }))
})
it('cancel leaves the procedure unchanged', () => {
  const { apply } = setup()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(apply).not.toHaveBeenCalled()
})
it('keeps failed variable resolution in the panel without applying', async () => {
  vi.spyOn(definitionsApi, 'getDraft').mockRejectedValue(new Error('offline'))
  const { apply } = setup()
  fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
  expect(await screen.findByText('offline')).toBeInTheDocument()
  expect(apply).not.toHaveBeenCalled()
})
it('does not apply a delayed response after leaving the editor', async () => {
  let resolve!: (value: typeof draft) => void
  vi.spyOn(definitionsApi, 'getDraft').mockImplementation(() => new Promise(done => { resolve = done }))
  const { apply, unmount } = setup()
  fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
  unmount()
  await act(async () => resolve(draft))
  expect(apply).not.toHaveBeenCalled()
})
