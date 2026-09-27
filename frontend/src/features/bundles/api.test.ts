import { afterEach, describe, expect, it, vi } from 'vitest'
import { bundleApi } from './api'
import type { GlobalBinding, ProjectBundleDocument } from './types'

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

  it('posts the plan payload to the v3 target endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        valid: true,
        targetProjectUuid: 'target-id',
        targetVersion: 1,
        bundleChecksum: 'checksum',
        planDigest: 'digest-1',
        counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
        changes: {},
        globalDependencies: [],
        issues: [],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })

    const binding: GlobalBinding = { type: 'CONNECTION', sourceCode: 'SRC', mode: 'BIND_EXISTING', targetUuid: 'conn-1' }
    const result = await bundleApi.planTargetImport('target-id', document, [binding])

    expect(result.planDigest).toBe('digest-1')
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/target-id/bundle/plan')
    expect(init.method).toBe('POST')
    const body = JSON.parse(String(init.body))
    expect(body.bundle.format).toBe('akis.project-bundle')
    expect(body.globalBindings).toEqual([binding])
  })

  it('sends the Idempotency-Key header on v3 target import', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        imported: true,
        replayed: false,
        targetProjectUuid: 'target-id',
        targetVersion: 2,
        bundleChecksum: 'checksum',
        planDigest: 'digest-1',
        counts: { folders: 0, definitions: 0, drafts: 0, versions: 0 },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'idem-key-7' })

    const result = await bundleApi.importIntoTarget('target-id', {
      bundle: document,
      globalBindings: [],
      planDigest: 'digest-1',
      targetVersion: 2,
    }, 'idem-key-7')

    expect(result.imported).toBe(true)
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/target-id/bundle/import')
    expect(init.method).toBe('POST')
    const headers = new Headers(init.headers)
    expect(headers.get('Idempotency-Key')).toBe('idem-key-7')
    const body = JSON.parse(String(init.body))
    expect(body.planDigest).toBe('digest-1')
    expect(body.targetVersion).toBe(2)
  })
})
