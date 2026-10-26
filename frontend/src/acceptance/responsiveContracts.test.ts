import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const shellStyles = readFileSync('src/styles.css', 'utf8')
const themeStyles = readFileSync('src/core/theme/ant-design.css', 'utf8')
const definitionStyles = readFileSync('src/features/definitions/definitions.css', 'utf8')

describe('responsive and input accessibility contracts', () => {
  it('keeps compact navigation and coarse-pointer targets available', () => {
    expect(shellStyles).toContain('@media (max-width: 820px)')
    expect(themeStyles).toContain('@media (pointer: coarse)')
    expect(themeStyles).toMatch(/min-height:\s*44px/)
    expect(themeStyles).toContain('focus-visible')
  })

  it('provides a focused package canvas, contextual inspector and narrow authoring layout', () => {
    expect(definitionStyles).toContain('.package-canvas')
    expect(definitionStyles).toContain('.package-properties')
    expect(definitionStyles).not.toContain('.package-accessible-list')
    expect(definitionStyles).toContain('@media (max-width: 820px)')
    expect(definitionStyles).toContain('.procedure-task-grid')
  })
})
