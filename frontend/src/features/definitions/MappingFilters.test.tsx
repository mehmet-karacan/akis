import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MappingFilters } from './MappingFilters'
import { definitionsApi } from './api'
import { DEFAULT_MAPPING } from './defaults'
import type { MappingContent } from './types'

vi.mock('./i18n', () => ({ useDefinitionsI18n: () => ({ language: 'en' }) }))
vi.mock('./api', () => ({ definitionsApi: { compileMappingExpression: vi.fn() } }))
vi.mock('./MappingExpressionInput', () => ({ MappingExpressionInput: ({ text, label, onChange }: { text: string; label: string; onChange(text: string): void }) => <textarea aria-label={label} value={text} onChange={event => onChange(event.target.value)} /> }))
const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'SRC' }, { id: 'S2', alias: 'OTHER' }], filters: [] }
const columns = { S1: [{ reference: 'ID', producerType: 'NUMBER', canonicalType: 'INTEGER', ordinal: 1, nullable: false }] }
const predicate = { kind: 'BINARY', operator: '>', left: { kind: 'COLUMN', dataset: 'S1', column: 'ID' }, right: { kind: 'LITERAL', value: 10 } }
const onChange = vi.fn()
beforeEach(() => vi.clearAllMocks())
it('adds a validated predicate with source-only catalog and no legacy operator fields', async () => {
  vi.mocked(definitionsApi.compileMappingExpression).mockResolvedValue({ expression: predicate, references: [] })
  render(<MappingFilters projectUuid="project" value={value} columns={columns} onChange={onChange} />)
  fireEvent.click(screen.getByRole('button', { name: 'Add Filter' }))
  expect(onChange).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('SQL Condition'), { target: { value: 'SRC.ID > 10' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Filter' }))
  await waitFor(() => expect(onChange).toHaveBeenCalledOnce())
  expect(definitionsApi.compileMappingExpression).toHaveBeenCalledWith('project', { sql: 'SRC.ID > 10', predicate: true, sources: [{ object: 'S1', alias: 'SRC', columns: ['ID'] }] })
  expect(onChange.mock.calls[0]?.[0].filters).toEqual([{ id: 'FILTER_1', scope: 'SOURCE', object: 'S1', predicate }])
})
it('preserves the existing legacy filter when its replacement is invalid or cancelled', async () => {
  vi.mocked(definitionsApi.compileMappingExpression).mockRejectedValue(new Error('Invalid predicate'))
  render(<MappingFilters projectUuid="project" value={{ ...value, filters: [{ id: 'F1', scope: 'GLOBAL', object: 'S1', column: 'ID', operator: 'GREATER_THAN', value: '10' }] }} columns={columns} onChange={onChange} />)
  fireEvent.click(screen.getByRole('button', { name: 'Edit filter F1' }))
  expect(screen.getByLabelText('SQL Condition')).toHaveValue("SRC.ID > '10'")
  fireEvent.change(screen.getByLabelText('SQL Condition'), { target: { value: 'DELETE FROM SRC' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply Filter' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid predicate')
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(onChange).not.toHaveBeenCalled()
  expect(screen.getByText("SRC.ID > '10'")).toBeVisible()
})
