import { fireEvent, screen } from '@testing-library/react'

export async function selectAntOption(control: HTMLElement, label: string | RegExp) {
  fireEvent.mouseDown(control)
  const option = await screen.findByRole('option', { name: label })
  fireEvent.click(option)
}
