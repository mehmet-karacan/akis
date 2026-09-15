import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { DataGrid } from './DataGrid'
vi.mock('./useRecordAudit', () => ({ useRecordAudit: () => ({ state: 'ready', records: { 'record-1': { uuid: 'record-1', createdBy: 'Creator', updatedBy: 'Editor', createdAt: '2026-09-15T09:00:00Z', updatedAt: '2026-09-15T10:00:00Z' } } }) }))

it('keeps audit attribution and semantic icons in every view', () => {
  const { container } = render(<DataGrid auditKind="models"><thead><tr><th>Name</th></tr></thead><tbody><tr key="record-1"><td>Model</td></tr></tbody></DataGrid>)
  for (const view of ['Table', 'Cards', 'List']) {
    fireEvent.click(screen.getByRole('radio', { name: view }))
    expect(screen.getByText('Creator')).toBeInTheDocument()
    expect(screen.getByText('Editor')).toBeInTheDocument()
    expect(container.querySelector('[data-field-icon="createdBy"]')).toBeInTheDocument()
    expect(container.querySelector('[data-field-icon="updatedBy"]')).toBeInTheDocument()
    expect(container.querySelector('[data-field-icon="createdAt"]')).toBeInTheDocument()
  }
})

it('uses common views without losing record values or actions', () => {
  const edit = vi.fn()
  render(<DataGrid><thead><tr><th>Name</th><th>Action</th></tr></thead><tbody><tr><td>Finance</td><td><button onClick={edit}>Edit</button></td></tr></tbody></DataGrid>)
  fireEvent.click(screen.getByRole('radio', { name: 'Table' }))
  expect(screen.getByRole('table')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('radio', { name: 'Cards' }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.getByText('Finance')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
  expect(edit).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('radio', { name: 'List' }))
  expect(screen.getByRole('listitem')).toHaveTextContent('Finance')
  fireEvent.click(screen.getByRole('radio', { name: 'Table' }))
  expect(screen.getByRole('table')).toBeInTheDocument()
})

it('does not duplicate an external view selector', () => {
  render(<DataGrid viewControls={false}><thead><tr><th>Name</th></tr></thead><tbody><tr><td>Finance</td></tr></tbody></DataGrid>)
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  expect(screen.getByRole('table')).toBeInTheDocument()
})

it('uses the model name as card identity instead of a numeric column', () => {
  const { container } = render(<DataGrid><thead><tr><th>Count</th><th>Model name</th></tr></thead><tbody><tr><td>1</td><td>Finance Model</td></tr></tbody></DataGrid>)
  fireEvent.click(screen.getByRole('radio', { name: 'Cards' }))
  expect(container.querySelector('.ui-grid-record header')).toHaveTextContent('Finance Model')
  expect(container.querySelector('.ui-grid-record header')).not.toHaveTextContent('1')
})
