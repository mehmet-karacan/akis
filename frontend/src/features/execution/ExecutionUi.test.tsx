import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../../core/i18n'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { RunStatusBadge } from './RunStatusBadge'

describe('execution UI states', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('explains that manual requests are disabled without hiding inspection access', () => {
    render(<ExecutionDisabledNotice />)

    expect(screen.getByText('Execution requests are disabled')).toBeInTheDocument()
    expect(screen.getByText(/Existing runs and recorded events remain available/)).toBeInTheDocument()
  })

  it('renders a canonical running status as static recorded state', () => {
    render(<RunStatusBadge status="CALISIYOR" />)

    const badge = screen.getByText('Running')
    expect(badge).toHaveClass('run-status--calisiyor')
    expect(badge).not.toHaveClass('ops-spin')
    expect(badge).not.toHaveAttribute('aria-busy')
  })

  it('localizes every canonical control-plane status', () => {
    const statuses = [
      'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
      'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
      'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
      'BASARILI', 'BASARISIZ', 'IPTAL',
    ]

    for (const status of statuses) {
      const { unmount } = render(<RunStatusBadge status={status} />)
      expect(screen.queryByText(status)).not.toBeInTheDocument()
      unmount()
    }
  })
})
