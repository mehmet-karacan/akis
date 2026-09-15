import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { Select } from './Select'
import { selectAntOption } from '../../test/selectAntOption'

it('serializes the chosen value and preserves a native required constraint', async () => {
  const changed = vi.fn()
  const { container } = render(<form><label>Schema<Select required name="schema" defaultValue="" onChange={changed}><option value="">Choose</option><option value="logical-1">Finance</option></Select></label></form>)
  const form = container.querySelector('form')!
  expect(form.checkValidity()).toBe(false)
  await selectAntOption(screen.getByRole('combobox', { name: 'Schema' }), 'Finance')
  expect(new FormData(form).get('schema')).toBe('logical-1')
  expect(form.checkValidity()).toBe(true)
  expect(changed).toHaveBeenCalledWith({ target: { value: 'logical-1' }, currentTarget: { value: 'logical-1' } })
})

it('inherits disabled fieldsets including changes after mount', async () => {
  const { rerender } = render(<fieldset disabled><Select aria-label="Schema" defaultValue="a"><option value="a">A</option></Select></fieldset>)
  expect(screen.getByRole('combobox', { name: 'Schema' })).toBeDisabled()
  rerender(<fieldset><Select aria-label="Schema" defaultValue="a"><option value="a">A</option></Select></fieldset>)
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Schema' })).not.toBeDisabled())
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Schema' }))
  expect(await screen.findByRole('option', { name: 'A' })).toBeInTheDocument()
})
