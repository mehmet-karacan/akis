import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DEFINITION_TYPE_TONES, DefinitionTypeIcon } from './DefinitionTypeIcon'
import { DEFINITION_TYPES } from './types'

describe('DefinitionTypeIcon', () => {
  it.each(DEFINITION_TYPES)('renders a distinct catalog icon hook for %s', (type) => {
    const { container } = render(<DefinitionTypeIcon type={type} />)
    expect(container.querySelector(`[data-definition-type="${type}"]`)).toBeInTheDocument()
  })

  it('keeps package and knowledge-module summary tones distinct', () => {
    expect(DEFINITION_TYPE_TONES.PACKAGE).toBe('neutral')
    expect(DEFINITION_TYPE_TONES.KNOWLEDGE_MODULE).toBe('brown')
  })
})
