import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DefinitionTypeIcon } from './DefinitionTypeIcon'
import { DEFINITION_TYPES } from './types'

describe('DefinitionTypeIcon', () => {
  it.each(DEFINITION_TYPES)('renders a distinct catalog icon hook for %s', (type) => {
    const { container } = render(<DefinitionTypeIcon type={type} />)
    expect(container.querySelector(`[data-definition-type="${type}"]`)).toBeInTheDocument()
  })
})
