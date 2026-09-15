import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi, type ConnectionCatalogProjection } from '../topology/api'
import { ConnectionsPage } from './ConnectionsPage'
import { ProjectAccessProvider } from '../../core/auth/ProjectAccessContext'

const catalog = Array.from({ length: 30 }, (_, index): ConnectionCatalogProjection => ({
  connection: { uuid: `connection-${index}`, code: `SKY_${String(index).padStart(2, '0')}`, databaseType: 'ORACLE', status: 'AKTIF', name: `Sky ${String(index).padStart(2, '0')}`, version: 1 },
  displayedVersion: { uuid: `version-${index}`, versionNumber: 1, mode: 'JDBC', host: 'db.example', port: 1521, serviceName: 'ORCL', policyVersion: 2, policy: {}, createdAt: '2026-01-01T00:00:00Z', lifecycleStatus: 'ACTIVE', lifecycleVersion: 1, runtimeCapability: 'EXECUTABLE' },
  latestVersionNumber: 1,
  physicalSchemaCount: 2,
  logicalSchemaCount: 1,
}))

function LocationProbe() { return <output data-testid="location">{useLocation().search}</output> }

describe('ConnectionsPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.spyOn(topologyApi, 'listConnectionCatalog').mockResolvedValue(catalog)
  })

  it('keeps filters in the URL and progressively reveals records', async () => {
    const { container } = render(<MemoryRouter initialEntries={['/projects/project/connections?q=sky&provider=ORACLE&status=ACTIVE&sort=code&page=2']}><Routes><Route path="/projects/:projectUuid/connections" element={<><ConnectionsPage /><LocationProbe /></>} /></Routes></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: 'Records' })).toBeInTheDocument()
    expect(container.querySelectorAll('.connection-record-card')).toHaveLength(25)
    fireEvent.click(screen.getByRole('button', { name: 'Load More' }))
    expect(container.querySelectorAll('.connection-record-card')).toHaveLength(30)
    expect(screen.getByTestId('location')).toHaveTextContent('page=2')
    fireEvent.click(screen.getByRole('button', { name: 'Show Filters' }))
    fireEvent.change(screen.getByPlaceholderText('Search by name, code or provider'), { target: { value: 'SKY_01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(screen.getByTestId('location')).not.toHaveTextContent('page=2'))
    expect(screen.getByRole('heading', { name: 'Records' })).toBeInTheDocument()
  })

  it('does not offer a write action to a read-only operator', async () => {
    render(<ProjectAccessProvider value={{ roles: ['OPERASYON'], permissions: ['BAGLANTI_GORUNTULE'] }}><MemoryRouter initialEntries={['/projects/project/connections']}><Routes><Route path="/projects/:projectUuid/connections" element={<ConnectionsPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)
    expect(await screen.findByRole('heading', { name: 'Records' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add Connection' })).not.toBeInTheDocument()
  })
})
