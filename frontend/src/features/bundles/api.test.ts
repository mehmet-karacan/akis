import { afterEach, describe, expect, it, vi } from 'vitest'
import { bundleApi } from './api'
import type { ProjectBundleDocument } from './types'

const document = {
  format: 'akis.project-bundle',
  formatVersion: 2,
  schemaVersion: 2,
  checksum: 'a'.repeat(64),
  exportedAt: '2026-09-11T00:00:00Z',
  project: { code: 'DEMO', status: 'AKTIF', name: 'Demo', description: null },
  folders: [],
  definitions: [],
  topology: { sanitized: true, definitions: {} },
} satisfies ProjectBundleDocument

afterEach(() => vi.unstubAllGlobals())

describe('bundle API', () => {
  it('uses the required dry-run and conflict query parameters', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ imported: false, dryRun: true }),
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })

    await bundleApi.importProject(document, 'RENAME', true)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/project-bundles/import?conflict=RENAME&dryRun=true')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body)).format).toBe('akis.project-bundle')
  })

  it('encodes the project id in the export endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => document })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })

    await bundleApi.exportProject('project/id')

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project%2Fid/bundle/export')
  })
})
