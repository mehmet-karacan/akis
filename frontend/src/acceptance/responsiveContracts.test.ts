import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const shellStyles = readFileSync('src/styles.css', 'utf8')
const definitionStyles = readFileSync('src/features/definitions/definitions.css', 'utf8')

describe('responsive and input accessibility contracts', () => {
  it('keeps compact navigation and coarse-pointer targets available', () => {
    expect(shellStyles).toContain('@media (max-width: 820px)')
    expect(shellStyles).toContain('@media (pointer: coarse)')
    expect(shellStyles).toMatch(/min-height:\s*44px/)
    expect(shellStyles).toContain('focus-visible')
  })

  it('provides a non-graph package representation and narrow authoring layout', () => {
    expect(definitionStyles).toContain('.package-accessible-list')
    expect(definitionStyles).toContain('@media (max-width: 820px)')
    expect(definitionStyles).toContain('.procedure-task-grid')
  })
})
