import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SqlEditor } from './SqlEditor'

describe('SqlEditor', () => {
  it('formats Oracle SQL only when the user requests it', async () => {
    const onChange = vi.fn()
    render(<SqlEditor label="Target SQL" value="select id,name from target_table where id=:id" onChange={onChange} formatLabel="SQL Biçimlendir" />)

    fireEvent.click(screen.getByRole('button', { name: 'SQL Biçimlendir' }))

    await waitFor(() => expect(onChange).toHaveBeenCalledOnce())
    expect(onChange.mock.calls[0]?.[0]).toContain('SELECT')
    expect(onChange.mock.calls[0]?.[0]).toContain('\nFROM')
  })

  it('does not offer formatting for a read-only editor', () => {
    render(<SqlEditor label="Target SQL" value="SELECT 1 FROM DUAL" onChange={vi.fn()} readOnly formatLabel="SQL Biçimlendir" />)
    expect(screen.queryByRole('button', { name: 'SQL Biçimlendir' })).not.toBeInTheDocument()
  })
})
