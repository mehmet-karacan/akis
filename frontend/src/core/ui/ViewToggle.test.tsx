import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it } from 'vitest'
import i18n from '../i18n'
import { ViewToggle, useCollectionView } from './ViewToggle'

function Harness() { const [view, setView] = useCollectionView('test:views'); return <ViewToggle value={view} onChange={setView} /> }
beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('en') })
it('defaults to cards and remembers a valid selection', () => {
  const { unmount } = render(<Harness />)
  expect(screen.getByRole('button', { name: 'Cards' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: 'Table' }))
  unmount(); render(<Harness />)
  expect(screen.getByRole('button', { name: 'Table' })).toHaveAttribute('aria-pressed', 'true')
})
it('ignores invalid saved values', () => {
  localStorage.setItem('test:views', 'invalid')
  render(<Harness />)
  expect(screen.getByRole('button', { name: 'Cards' })).toHaveAttribute('aria-pressed', 'true')
})
