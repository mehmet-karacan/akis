import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import '../../core/i18n'
import { KmRunDetails, type KmRunData } from './KmRunDetails'

const data: KmRunData = { workObjects: [], steps: [
  { generation: 1, ordinal: 1, stepCode: 'LOAD', operation: 'TRANSFER_JDBC', site: 'STAGING', slot: 'WORK_SOURCE_1', state: 'SUCCEEDED', affectedRows: 1201, errorCode: null, startedAt: null, completedAt: null },
  { generation: 1, ordinal: 2, stepCode: 'PUBLISH', operation: 'ATOMIC_REPLACE', site: 'TARGET', slot: 'WORK_SOURCE_1', state: 'UNKNOWN', affectedRows: null, errorCode: 'KM_RECONCILIATION_REQUIRED', startedAt: null, completedAt: null },
] }
describe('KM run evidence', () => {
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
})
