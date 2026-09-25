import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { LegacyDefinitionsRedirect, LegacyRunsRedirect, LegacyTopologyRedirect } from './App'

function LocationProbe() { return <output>{useLocation().pathname}{useLocation().search}</output> }
function renderRoute(initial: string, legacyPath: string, element: React.ReactNode) {
  render(<MemoryRouter initialEntries={[initial]}><Routes><Route path={legacyPath} element={element} /><Route path="*" element={<LocationProbe />} /></Routes></MemoryRouter>)
  return screen.findByRole('status')
}

describe('legacy route compatibility', () => {
  it('preserves secondary definition filters while producing a canonical object URL', async () => {
    expect(await renderRoute('/projects/p1/definitions?definition=d1&tab=versions', '/projects/:projectUuid/definitions', <LegacyDefinitionsRedirect />)).toHaveTextContent('/project/objects/definitions/d1?tab=versions')
  })
  it('redirects old run detail URLs without losing the query', async () => {
    expect(await renderRoute('/projects/p1/runs/r1?tab=events', '/projects/:projectUuid/runs/:runUuid', <LegacyRunsRedirect detail />)).toHaveTextContent('/project/operations?tab=events&run=r1')
  })
  it('redirects topology to the connection workspace', async () => {
    expect(await renderRoute('/projects/p1/topology', '/projects/:projectUuid/topology', <LegacyTopologyRedirect />)).toHaveTextContent('/project/connections')
  })
})
