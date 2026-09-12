import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Dialog } from './Dialog'

function Harness() {
  const [open, setOpen] = useState(false)
  return <><button onClick={() => setOpen(true)}>Open</button><Dialog open={open} title="Accessible dialog" closeLabel="Close" onClose={() => setOpen(false)}><button>First action</button><button>Last action</button></Dialog></>
}

describe('Dialog', () => {
  it('closes with Escape and restores focus to its trigger', () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open' })
    trigger.focus()
    fireEvent.click(trigger)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('keeps keyboard focus inside the dialog', () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Open' }))
    const close = screen.getByRole('button', { name: 'Close' })
    const last = screen.getByRole('button', { name: 'Last action' })
    last.focus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(close).toHaveFocus()
  })

  it('makes sibling content inert while open and restores it on close', () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open' })
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-hidden', 'true')
    expect(trigger).toHaveProperty('inert', true)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(trigger).not.toHaveAttribute('aria-hidden')
    expect(trigger.inert).not.toBe(true)
  })
})
