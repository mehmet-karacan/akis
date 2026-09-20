import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { topologyApi, type ConnectionCatalogProjection } from '../topology/api'
import { ConnectionsPage } from './ConnectionsPage'
import { ProjectAccessProvider } from '../../core/auth/ProjectAccessContext'
import { connectionFixture } from '../topology/testFixtures'

const catalog = Array.from({ length: 30 }, (_, index): ConnectionCatalogProjection => ({
  connection: connectionFixture({ uuid: `connection-${index}`, code: `SKY_${String(index).padStart(2, '0')}`, name: `Sky ${String(index).padStart(2, '0')}`, lastTestedAt: '2026-01-01T00:00:00Z', lastTestPassed: true }),
  physicalSchemaCount: 2,
  logicalSchemaCount: 1,
}))

function LocationProbe() { return <output data-testid="location">{useLocation().search}</output> }

describe('ConnectionsPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.spyOn(topologyApi, 'listConnectionCatalog').mockResolvedValue(catalog)
  })

  // This integration case renders 30 rich records and queries their accessibility tree.
  // Keep the assertions intact; jsdom on the Windows CI host can exceed the 5s unit default.
  it('keeps filters in the URL and progressively reveals records', { timeout: 15000 }, async () => {
    const { container } = render(<MemoryRouter initialEntries={['/projects/project/connections?q=sky&provider=ORACLE&status=ACTIVE&sort=code&page=2']}><Routes><Route path="/projects/:projectUuid/connections" element={<><ConnectionsPage /><LocationProbe /></>} /></Routes></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: 'Connection Catalog' })).toBeInTheDocument()
    expect(container.querySelectorAll('.ui-grid-record')).toHaveLength(25)
    fireEvent.click(screen.getByText('Load More').closest('button')!)
    expect(container.querySelectorAll('.ui-grid-record')).toHaveLength(30)
    expect(screen.getByTestId('location')).toHaveTextContent('page=2')
    fireEvent.click(screen.getByText('Show Filters').closest('button')!)
    fireEvent.change(screen.getByPlaceholderText('Search by name, code or provider'), { target: { value: 'SKY_01' } })
    fireEvent.click(screen.getByText('Search', { exact: true }).closest('button')!)
    await waitFor(() => expect(screen.getByTestId('location')).not.toHaveTextContent('page=2'))
    expect(screen.getByRole('heading', { name: 'Connection Catalog' })).toBeInTheDocument()
  })

  it('does not offer a write action to a read-only operator', async () => {
    // Permission behavior needs one real record; pagination is covered above with 30.
    vi.mocked(topologyApi.listConnectionCatalog).mockResolvedValue(catalog.slice(0, 1))
    render(<ProjectAccessProvider value={{ roles: ['OPERASYON'], permissions: ['BAGLANTI_GORUNTULE'] }}><MemoryRouter initialEntries={['/projects/project/connections']}><Routes><Route path="/projects/:projectUuid/connections" element={<ConnectionsPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)
    expect(await screen.findByRole('heading', { name: 'Connection Catalog' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add Connection' })).not.toBeInTheDocument()
  })

  it('opens details from the single view action', async () => {
    vi.mocked(topologyApi.listConnectionCatalog).mockResolvedValue(catalog.slice(0, 1))
    render(<ProjectAccessProvider value={{ roles: ['PROJE_YONETICISI'], permissions: ['BAGLANTI_GORUNTULE', 'BAGLANTI_YONET'] }}><MemoryRouter initialEntries={['/projects/project/connections']}><Routes><Route path="/projects/:projectUuid/connections" element={<ConnectionsPage />} /></Routes></MemoryRouter></ProjectAccessProvider>)

    expect(await screen.findByRole('heading', { name: 'Connection Catalog' })).toBeInTheDocument()
    const view = screen.getByRole('button', { name: 'View: Sky 00' })
    fireEvent.click(view)
    expect(screen.queryByRole('menuitem', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
