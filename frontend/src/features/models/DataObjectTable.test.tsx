import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import i18n from '../../core/i18n'
import { DataObjectTable } from './DataObjectTable'

it('shows native database types and temporal precision without canonical type or size prefixes', async () => {
  await i18n.changeLanguage('en')
  render(<DataObjectTable columns={[
    { reference: 'CREATED_AT', producerType: 'TIMESTAMP(6)', canonicalType: 'TIMESTAMP', ordinal: 1, timePrecision: 6, nullable: false },
    { reference: 'AMOUNT', producerType: 'NUMBER(19,2)', canonicalType: 'DECIMAL', ordinal: 2, precision: 19, scale: 2, nullable: true },
    { reference: 'NAME', producerType: 'VARCHAR2(200)', canonicalType: 'STRING', ordinal: 3, length: 200, nullable: true },
  ]} />)
  fireEvent.click(screen.getByRole('radio', { name: 'Table' }))
  expect(screen.getByText('CREATED_AT').closest('tr')).toHaveTextContent('TIMESTAMP(6)6')
  expect(screen.getByText('AMOUNT').closest('tr')).toHaveTextContent('NUMBER(19,2)19, 2')
  expect(screen.getByText('NAME').closest('tr')).toHaveTextContent('VARCHAR2(200)200')
  expect(screen.queryByText('DECIMAL')).not.toBeInTheDocument()
  expect(screen.queryByText('STRING')).not.toBeInTheDocument()
  expect(screen.queryByText(/^[PL]:/)).not.toBeInTheDocument()
})
