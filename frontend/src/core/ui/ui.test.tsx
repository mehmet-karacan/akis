import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AsyncState, Button, Field, PageHeader, StatusBadge } from '.'

describe('shared UI primitives', () => {
  it('connects field labels and errors to their control', () => {
    render(<Field label="Connection name" error="Required"><input /></Field>)
    const input = screen.getByLabelText('Connection name')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Required')
  })

  it('announces loading and supports retry after an error', () => {
    const retry = vi.fn()
    const { rerender } = render(<AsyncState state="loading" title="Loading" />)
    expect(screen.getByRole('status')).toHaveTextContent('Loading')
    rerender(<AsyncState state="error" title="Could not load" retryLabel="Try again" onRetry={retry} />)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('provides consistent header, status and busy button semantics', () => {
    render(<><PageHeader title="Connections" description="Manage connections" /><StatusBadge tone="success">Active</StatusBadge><Button busy busyLabel="Saving">Save</Button></>)
    expect(screen.getByRole('heading', { name: 'Connections' })).toBeInTheDocument()
    expect(screen.getByText('Active')).toHaveClass('success')
    expect(screen.getByRole('button', { name: 'Saving' })).toBeDisabled()
  })
})
