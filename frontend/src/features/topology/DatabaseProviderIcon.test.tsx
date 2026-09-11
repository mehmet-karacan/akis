import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DatabaseProviderIcon, databaseProviderVisual } from './DatabaseProviderIcon'

describe('DatabaseProviderIcon', () => {
  it('renders an accessible Oracle provider identity', () => {
    render(<DatabaseProviderIcon databaseType="ORACLE" />)

    const icon = screen.getByRole('img', { name: 'Oracle database' })
    expect(icon).toHaveAttribute('data-provider', 'oracle')
    expect(icon).toHaveTextContent('O')
  })

  it('normalizes known provider aliases and keeps an unknown provider legible', () => {
    expect(databaseProviderVisual('postgres_sql')).toMatchObject({ label: 'PostgreSQL', tone: 'postgresql' })
    expect(databaseProviderVisual('Snowflake')).toMatchObject({ label: 'Snowflake', monogram: 'S', tone: 'generic' })
  })
})
