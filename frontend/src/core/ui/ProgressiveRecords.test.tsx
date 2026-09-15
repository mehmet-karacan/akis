import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ProgressiveRecords } from './ProgressiveRecords'

afterEach(() => vi.unstubAllGlobals())
it('reveals the next batch when the scroll sentinel enters the viewport', () => {
  let intersect: IntersectionObserverCallback
  const disconnect = vi.fn()
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { intersect = callback }
    observe() {}
    disconnect = disconnect
  })
  const { unmount } = render(<ProgressiveRecords items={[1, 2, 3, 4, 5]} batchSize={2}>{items => <ul>{items.map(item => <li key={item}>{item}</li>)}</ul>}</ProgressiveRecords>)
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  act(() => intersect([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
  expect(screen.getAllByRole('listitem')).toHaveLength(4)
  act(() => intersect([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
  expect(screen.getAllByRole('listitem')).toHaveLength(5)
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  unmount()
  expect(disconnect).toHaveBeenCalled()
})
