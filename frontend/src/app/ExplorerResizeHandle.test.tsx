import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { clampExplorerWidth, ExplorerResizeHandle } from './ExplorerResizeHandle'

it('keeps space for both explorer and editor at narrow and wide sizes', () => {
  expect(clampExplorerWidth(800, 1440)).toBe(520)
  expect(clampExplorerWidth(800, 1000)).toBe(450)
  expect(clampExplorerWidth(10, 1440)).toBe(224)
  expect(clampExplorerWidth(Number.NaN, 1440)).toBe(264)
})
it('supports keyboard sizing and resetting without a mouse', () => {
  const onChange = vi.fn()
  render(<ExplorerResizeHandle width={300} onChange={onChange} />)
  const separator = screen.getByRole('separator')
  fireEvent.keyDown(separator, { key: 'ArrowRight' })
  expect(onChange).toHaveBeenLastCalledWith(316)
  fireEvent.keyDown(separator, { key: 'Home' })
  expect(onChange).toHaveBeenLastCalledWith(224)
  fireEvent.doubleClick(separator)
  expect(onChange).toHaveBeenLastCalledWith(264)
})
