import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { QueryFilter } from './QueryFilter'
import i18n from '../i18n'

describe('QueryFilter', () => {
  it('applies only on submission and clears draft and applied query together', async () => {
    await i18n.changeLanguage('en')
    const apply = vi.fn()
    render(<QueryFilter onApply={apply} placeholder="Find records" />)
    const toggle = screen.getByRole('button', { name: /filters/i })
    if (toggle.getAttribute('aria-expanded') === 'false') fireEvent.click(toggle)
    const input = screen.getByPlaceholderText('Find records')
    fireEvent.change(input, { target: { value: ' GPU ' } })
    expect(apply).not.toHaveBeenCalled()
    fireEvent.submit(input.closest('form')!)
    expect(apply).toHaveBeenLastCalledWith('GPU')
    fireEvent.click(screen.getByRole('button', { name: /clear|temizle/i }))
    expect(apply).toHaveBeenLastCalledWith('')
    expect(input).toHaveValue('')
  })
})
