import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { useState } from 'react'
import i18n from '../../core/i18n'
import { KnowledgeIntegerInput, validKnowledgeInteger } from './KnowledgeIntegerInput'

beforeEach(async () => { await i18n.changeLanguage('en') })
it('validates signed 64-bit text without accepting rounded JS numbers', () => {
  for (const value of ['0', '9007199254740993', '9223372036854775807', '-9223372036854775808', 42]) expect(validKnowledgeInteger(value)).toBe(true)
  for (const value of ['1.5', '1e3', '9223372036854775808', '-9223372036854775809', ' 1', '', '1;DROP', 9007199254740992, Infinity, NaN, 1.5]) expect(validKnowledgeInteger(value)).toBe(false)
})
it('retains exact and invalid input on blur, with a visible validation error', () => {
  const changed = vi.fn()
  function Harness() {
    const [value, setValue] = useState<string | undefined>('9007199254740993')
    return <KnowledgeIntegerInput label="Integer" value={value} onChange={next => { changed(next); setValue(next) }} />
  }
  render(<Harness />)
  const input = screen.getByRole('textbox', { name: 'Integer' })
  expect(input).toHaveValue('9007199254740993')
  fireEvent.change(input, { target: { value: '9223372036854775808' } }); fireEvent.blur(input)
  expect(input).toHaveValue('9223372036854775808')
  expect(input).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('alert')).toHaveTextContent('64-bit integer')
  fireEvent.change(input, { target: { value: '-9223372036854775808' } })
  expect(input).toHaveAttribute('aria-invalid', 'false')
  expect(changed).toHaveBeenLastCalledWith('-9223372036854775808')
  fireEvent.change(input, { target: { value: '' } })
  expect(changed).toHaveBeenLastCalledWith(undefined)
})
