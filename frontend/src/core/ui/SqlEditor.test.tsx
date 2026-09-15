import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SqlEditor } from './SqlEditor'

describe('SqlEditor', () => {
  it('keeps inline SQL editable without panel tools and highlights variables', () => {
    const { container } = render(<SqlEditor label="SQL" value="SELECT @RUN_DATE FROM t@REMOTE" onChange={vi.fn()} showToolbar={false} toolbar={<button>Expand</button>} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(container.querySelector('.cm-content')).toHaveAttribute('contenteditable', 'true')
    expect(container.querySelectorAll('.cm-project-variable')).toHaveLength(1)
    expect(container.querySelector('.cm-project-variable')).toHaveTextContent('@RUN_DATE')
  })
  it('checks only on request and discards results for an older command', async () => {
    let resolve!: (value: { line: number; message: string }[]) => void
    const validateSql = vi.fn(() => new Promise<{ line: number; message: string }[]>((done) => { resolve = done }))
    const props = { label: 'SQL', onChange: vi.fn(), validateSql }
    const { rerender } = render(<SqlEditor {...props} value="SELECT A FROM T" />)
    expect(validateSql).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Check SQL' }))
    expect(validateSql).toHaveBeenCalledWith('SELECT A FROM T')
    rerender(<SqlEditor {...props} value="SELECT B FROM T" />)
    resolve([{ line: 1, message: 'Old command error' }])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Check SQL' })).not.toBeDisabled())
    expect(screen.queryByText('Old command error')).not.toBeInTheDocument()
  })

  it('shows server diagnostics without executing or modifying SQL', async () => {
    const onChange = vi.fn()
    render(<SqlEditor label="SQL" value="SELECT A FROM T" onChange={onChange} validateSql={async () => [{ line: 1, message: 'Unsupported policy' }]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Check SQL' }))
    expect(await screen.findByText('Unsupported policy')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })
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
