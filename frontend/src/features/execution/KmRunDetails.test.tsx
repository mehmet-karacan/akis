import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import '../../core/i18n'
import { KmRunDetails, type KmRunData } from './KmRunDetails'

const data: KmRunData = { workObjects: [], steps: [
  { generation: 1, ordinal: 1, stepCode: 'LOAD', operation: 'TRANSFER_JDBC', site: 'STAGING', slot: 'WORK_SOURCE_1', state: 'SUCCEEDED', affectedRows: 1201, errorCode: null, startedAt: null, completedAt: null },
  { generation: 1, ordinal: 2, stepCode: 'PUBLISH', operation: 'ATOMIC_REPLACE', site: 'TARGET', slot: 'WORK_SOURCE_1', state: 'UNKNOWN', affectedRows: null, errorCode: 'KM_RECONCILIATION_REQUIRED', startedAt: null, completedAt: null },
] }
describe('KM run evidence', () => {
  it('shows live committed transfer metrics and updates when the next snapshot arrives', () => {
    const progress = { rows: 500, bytes: 1048576, elapsedSeconds: 10, readSeconds: 2, writeSeconds: 7, commitSeconds: 1 }
    const snapshot = { ...data, steps: [{ ...data.steps[0]!, state: 'RUNNING', affectedRows: 500, transferProgress: progress }] }
    const { rerender } = render(<KmRunDetails data={snapshot} />)
    const metrics = within(screen.getByRole('region', { name: /Transfer progress|Aktarım ilerlemesi/ }))
    expect(metrics.getByText('500')).toBeInTheDocument()
    expect(metrics.getByText('1 MiB')).toBeInTheDocument()
    expect(metrics.getByText(/50 (rows|satır)\/s/)).toBeInTheDocument()
    for (const value of ['10 s', '2 s', '7 s', '1 s']) expect(metrics.getByText(value)).toBeInTheDocument()
    expect(metrics.queryByRole('spinbutton')).not.toBeInTheDocument()
    rerender(<KmRunDetails data={{ ...snapshot, steps: [{ ...snapshot.steps[0]!, transferProgress: { ...progress, rows: 1000, bytes: 2097152, elapsedSeconds: 20 } }] }} />)
    expect(metrics.getByText('2 MiB')).toBeInTheDocument()
  })
  it('handles zero elapsed time without showing an infinite transfer rate', () => {
    render(<KmRunDetails data={{ ...data, steps: [{ ...data.steps[0]!, transferProgress: { rows: 0, bytes: 0, elapsedSeconds: 0, readSeconds: 0, writeSeconds: 0, commitSeconds: 0 } }] }} />)
    const metrics = within(screen.getByRole('region', { name: /Transfer progress|Aktarım ilerlemesi/ }))
    expect(metrics.getByText('0 B')).toBeInTheDocument()
    expect(metrics.queryByText(/NaN|Infinity/)).not.toBeInTheDocument()
    expect(metrics.getByText(/Not available|Kullanılamıyor/)).toBeInTheDocument()
  })
  it('keeps historical runs without progress readable', () => {
    render(<KmRunDetails data={data} />)
    expect(screen.queryByRole('region', { name: /Transfer progress|Aktarım ilerlemesi/ })).not.toBeInTheDocument()
  })
  it('never presents an unknown publication as successful', () => {
    render(<KmRunDetails data={data} />)
    expect(screen.getByText(/The target outcome must|Hedef sonucu doğrulanmalı/)).toBeInTheDocument()
  })
  it('shows reconciled outcome without erasing the original unknown step', () => {
    render(<KmRunDetails data={{ ...data, reconciliation: { outcome: 'PUBLISHED', rows: 1201 } }} />)
    expect(screen.getByText(/Target publication confirmed|Hedef yayını mutabakat/)).toBeInTheDocument()
    expect(screen.getByText(/Original step records|İlk çalıştırmanın adım/)).toBeInTheDocument()
    expect(screen.getAllByText(/Outcome Unknown|Sonuç Belirsiz/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/The target outcome must|Hedef sonucu doğrulanmalı/)).not.toBeInTheDocument()
  })
  it('renders the SQL evidence stored with the selected step', () => {
    render(<KmRunDetails data={{ ...data, steps: data.steps.map((step, index) => index === 0 ? {
      ...step,
      executedSql: [{ step: 'LOAD', site: 'SOURCE', owner: 'UPSTREAM_SCHEMA', sql: 'SELECT 1 FROM UPSTREAM_SCHEMA.CHANNEL' }],
    } : step) }} />)
    fireEvent.click(screen.getByRole('button', { name: /Load|Yükle/ }))
    expect(screen.getByText('1 SQL')).toBeInTheDocument()
    expect(screen.getByText('UPSTREAM_SCHEMA')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('data-language', 'sql')
  })
})
