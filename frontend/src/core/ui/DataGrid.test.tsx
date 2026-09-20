import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { DataGrid } from './DataGrid'
import { Table2 } from 'lucide-react'
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

it('uses the shared compact audit standard when audit is placed in the card footer', () => {
  const { container } = render(<DataGrid auditInFooter><thead><tr><th>Name</th><th>Created By</th><th>Created At</th><th>Actions</th></tr></thead><tbody><tr><td>Finance</td><td>Creator</td><td>18 Sep 2026</td><td><button>View</button></td></tr></tbody></DataGrid>)
  fireEvent.click(screen.getByRole('radio', { name: 'Cards' }))
  expect(container.querySelector('.ui-grid-footer-content .ui-record-audit--compact')).toHaveTextContent('Creator')
  expect(container.querySelector('.ui-grid-footer-action')).toHaveTextContent('View')
  fireEvent.click(screen.getByRole('radio', { name: 'List' }))
  expect(container.querySelector('.ui-grid-footer-content .ui-record-audit--footer')).toHaveTextContent('Creator')
  expect(container.querySelector('.ui-grid-footer-content .ui-record-audit--compact')).not.toBeInTheDocument()
})

it('can promote a sequence field to the right side of card headers while retaining it in list view', () => {
  const { container } = render(<DataGrid cardHeaderField="sequence"><thead><tr><th>Name</th><th>Sequence Used</th></tr></thead><tbody><tr><td>Orders</td><td>orders_id_seq</td></tr></tbody></DataGrid>)
  fireEvent.click(screen.getByRole('radio', { name: 'Cards' }))
  expect(container.querySelector('.ui-grid-record-header-extra')).toHaveTextContent('orders_id_seq')
  expect(container.querySelector('.ui-record-card-fields')).not.toHaveTextContent('orders_id_seq')
  fireEvent.click(screen.getByRole('radio', { name: 'List' }))
  expect(container.querySelector('.ui-grid-record-header-extra')).not.toBeInTheDocument()
  expect(container.querySelector('.ui-record-card-fields')).toHaveTextContent('orders_id_seq')
})

it('shows semantic collection and view icons when a collection title is present', () => {
  const { container } = render(<DataGrid collectionTitle="Table Catalog" collectionIcon={<Table2 data-testid="catalog-icon" />}><thead><tr><th>Name</th></tr></thead><tbody><tr><td>Orders</td></tr></tbody></DataGrid>)
  expect(screen.getByRole('heading', { name: 'Table Catalog' })).toContainElement(screen.getByTestId('catalog-icon'))
  expect(container.querySelector('.ui-grid-view-label svg')).toBeInTheDocument()
})

it('maps every schema metadata heading to its stable semantic icon', () => {
  const { container } = render(<DataGrid><thead><tr><th>Table</th><th>Description</th><th>Column Count</th><th>Constraint Count</th><th>Index Count</th><th>Relationship Count</th><th>Sequence Used</th><th>Created By</th><th>Created At</th><th>Updated By</th><th>Updated At</th><th>Actions</th></tr></thead><tbody><tr>{Array.from({ length: 12 }, (_, index) => <td key={index}>{index}</td>)}</tr></tbody></DataGrid>)
  fireEvent.click(screen.getByRole('radio', { name: 'Table' }))
  expect([...container.querySelectorAll('.ui-data-grid thead [data-field-icon]')].map(icon => icon.getAttribute('data-field-icon'))).toEqual([
    'table', 'description', 'columns', 'constraints', 'indexes', 'relationships', 'sequences',
    'createdBy', 'createdAt', 'updatedBy', 'updatedAt', 'action',
  ])
})
