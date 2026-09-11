import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Drawer } from './Drawer'

function Harness() {
  const [open, setOpen] = useState(false)
  return <><button onClick={() => setOpen(true)}>Open Drawer</button><Drawer open={open} title="Connection" closeLabel="Close" className="test-drawer" onClose={() => setOpen(false)}><button>Last Action</button></Drawer></>
}

describe('Drawer', () => {
  it('traps focus, closes with Escape, and restores the trigger', () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open Drawer' })
    trigger.focus()
    fireEvent.click(trigger)
    const close = screen.getByRole('button', { name: 'Close' })
    const last = screen.getByRole('button', { name: 'Last Action' })
    last.focus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(close).toHaveFocus()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })
})
