import { render, screen } from '@testing-library/react'
import { Activity } from 'lucide-react'
import { beforeEach, describe, expect, it } from 'vitest'
import i18n from '../i18n'
import { SummaryStrip } from './SummaryStrip'

describe('SummaryStrip', () => {
  beforeEach(async () => { await i18n.changeLanguage('tr') })

  it('formats numeric summary values with the active locale', () => {
    render(<SummaryStrip ariaLabel="Özet" items={[
      { label: 'Sayı', value: 123456, icon: <Activity /> },
      { label: 'Kesin sayı', value: '9876543210', icon: <Activity /> },
    ]} />)

    expect(screen.getByText('123.456')).toBeInTheDocument()
    expect(screen.getByText('9.876.543.210')).toBeInTheDocument()
  })
})
