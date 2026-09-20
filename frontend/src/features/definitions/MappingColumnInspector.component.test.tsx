import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MappingColumnInspector, type MappingInspectorContext } from './MappingColumnInspector'
import { definitionsApi } from './api'
import { DEFAULT_MAPPING } from './defaults'
import type { MappingContent } from './types'

vi.mock('./i18n', () => ({ useDefinitionsI18n: () => ({ language: 'en' }) }))
vi.mock('./api', () => ({ definitionsApi: { compileMappingExpression: vi.fn() } }))
vi.mock('./MappingExpressionInput', () => ({ MappingExpressionInput: ({ text, label, onChange }: { text: string; label: string; onChange(text: string): void }) => <textarea aria-label={label} value={text} onChange={event => onChange(event.target.value)} /> }))
const column = { reference: 'NAME', producerType: 'VARCHAR2(255)', canonicalType: 'STRING', ordinal: 1, nullable: true }
const columns = { SOURCE_1: [column], TARGET: [column] }
const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'SOURCE_1', alias: 'SRC' }], columnMappings: [] }
const context: MappingInspectorContext = { selection: { datasetId: 'TARGET', role: 'TARGET', column } }
const expression = { kind: 'CALL', function: 'UPPER', args: [{ kind: 'COLUMN', dataset: 'SOURCE_1', column: 'NAME' }] }
const onChange = vi.fn()
const ui = (selected = context) => <MappingColumnInspector projectUuid="project" context={selected} value={value} columns={columns} onChange={onChange} onClose={vi.fn()} onMaximize={vi.fn()} onAliasChange={vi.fn()} />
beforeEach(() => { vi.clearAllMocks() })
it('rejects a direct cross-family mapping and accepts an explicit conversion instead', async () => {
  const target = { ...column, producerType: 'NUMBER(10,0)', canonicalType: 'INTEGER' }
  render(ui({ selection: { ...context.selection, column: target } }))
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'SRC.NAME' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  expect(screen.getByRole('alert')).toHaveTextContent('incompatible for direct mapping')
  expect(onChange).not.toHaveBeenCalled()
  expect(definitionsApi.compileMappingExpression).not.toHaveBeenCalled()
  const converted = { ...expression, function: 'TO_NUMBER' }
  vi.mocked(definitionsApi.compileMappingExpression).mockResolvedValue({ expression: converted, references: [] })
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'TO_NUMBER(SRC.NAME)' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce())
  expect(onChange.mock.calls[0]?.[0].columnMappings[0].expression).toEqual(converted)
})
it('displays native temporal type and precision in the inspector', () => {
  render(ui({ selection: { ...context.selection, column: { ...column, producerType: 'TIMESTAMP(6)', canonicalType: 'TIMESTAMP', timePrecision: 6, length: 11 } } }))
  expect(screen.getByText('TIMESTAMP(6)', { exact: true })).toBeVisible()
  expect(screen.getByText('Length / Precision').closest('div')).toHaveTextContent('6')
  expect(screen.queryByText('11', { exact: true })).not.toBeInTheDocument()
})
it('removing a mapping invalidates a pending compilation immediately', async () => {
  let resolve!: (result: { expression: Record<string, unknown>; references: [] }) => void
  vi.mocked(definitionsApi.compileMappingExpression).mockReturnValue(new Promise(done => { resolve = done }))
  const content = { ...value, columnMappings: [{ source: { object: 'SOURCE_1', column: 'NAME' }, target: { object: 'TARGET', column: 'NAME' } }] }
  render(<MappingColumnInspector projectUuid="project" context={context} value={content} columns={columns} onChange={onChange} onClose={vi.fn()} onMaximize={vi.fn()} onAliasChange={vi.fn()} />)
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'UPPER(SRC.NAME)' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  fireEvent.click(screen.getByRole('button', { name: 'Remove Mapping' }))
  await act(async () => { resolve({ expression, references: [] }) })
  expect(onChange).toHaveBeenCalledOnce()
  expect(onChange.mock.calls[0]?.[0].columnMappings).toEqual([])
})
it('compiles free SQL and applies its AST to the selected target without saving the definition', async () => {
  vi.mocked(definitionsApi.compileMappingExpression).mockResolvedValue({ expression, references: [] })
  render(ui())
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'UPPER(SRC.NAME)' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce())
  expect(definitionsApi.compileMappingExpression).toHaveBeenCalledWith('project', { sql: 'UPPER(SRC.NAME)', sources: [{ object: 'SOURCE_1', alias: 'SRC', columns: ['NAME'] }] })
  expect(onChange.mock.calls[0]?.[0].columnMappings).toEqual([{ expression, target: { object: 'TARGET', column: 'NAME' } }])
})
it('keeps the existing mapping unchanged when SQL validation fails', async () => {
  vi.mocked(definitionsApi.compileMappingExpression).mockRejectedValue(new Error('Unsupported SQL function'))
  render(ui())
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'EVIL(SRC.NAME)' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Unsupported SQL function')
  expect(onChange).not.toHaveBeenCalled()
})
it('discards a delayed parse response after the selected column changes', async () => {
  let resolve!: (result: { expression: Record<string, unknown>; references: [] }) => void
  vi.mocked(definitionsApi.compileMappingExpression).mockReturnValue(new Promise(done => { resolve = done }))
  const rendered = render(ui())
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'UPPER(SRC.NAME)' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  rendered.rerender(ui({ selection: { ...context.selection, column: { ...column, reference: 'OTHER' } } }))
  resolve({ expression, references: [] })
  await waitFor(() => expect(screen.getByRole('button', { name: 'Apply Mapping' })).not.toHaveAttribute('aria-busy', 'true'))
  expect(onChange).not.toHaveBeenCalled()
})
it('keeps an unreadable saved expression intact and lets the user replace it explicitly', async () => {
  const original = { kind: 'UNKNOWN_VERSION', payload: 'opaque' }
  const content = { ...value, columnMappings: [{ expression: original, target: { object: 'TARGET', column: 'NAME' } }] }
  render(<MappingColumnInspector projectUuid="project" context={context} value={content} columns={columns} onChange={onChange} onClose={vi.fn()} onMaximize={vi.fn()} onAliasChange={vi.fn()} />)
  expect(screen.getByRole('alert')).toHaveTextContent('mapping is preserved')
  expect(onChange).not.toHaveBeenCalled()
  expect(content.columnMappings[0]?.expression).toEqual(original)
  fireEvent.change(screen.getByLabelText('Source Mapping'), { target: { value: 'SRC.NAME' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Mapping' }))
  expect(onChange).toHaveBeenCalledOnce()
  expect(onChange.mock.calls[0]?.[0].columnMappings[0]).toEqual({ source: { object: 'SOURCE_1', column: 'NAME' }, target: { object: 'TARGET', column: 'NAME' } })
})
