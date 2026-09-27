import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SourceTargetCard } from './SourceTargetCard'

describe('SourceTargetCard', () => {
  it('uses the source semantic identity and keeps the technical alias secondary', () => {
    const { container } = render(<SourceTargetCard role="SOURCE" label="Kaynak" name="Hakediş Tipi" technicalAlias="SRC_1" />)

    expect(screen.getByText('Kaynak')).toBeInTheDocument()
    expect(screen.getByText('Hakediş Tipi')).toBeInTheDocument()
    expect(screen.getByText('SRC_1')).toHaveClass('mapping-inspector-technical-alias')
    expect(container.querySelector('.procedure-heading-icon--source')).toBeInTheDocument()
  })

  it('uses the target semantic identity without inventing an alias', () => {
    const { container } = render(<SourceTargetCard role="TARGET" label="Hedef" name="Hedef Tablo" />)

    expect(container.querySelector('.procedure-heading-icon--target')).toBeInTheDocument()
    expect(container.querySelector('.mapping-inspector-technical-alias')).toBeNull()
  })
})
