import { afterEach, describe, expect, it, vi } from 'vitest'
import { operationsApi } from './api'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('operations API contracts', () => {
  it('posts the publication decision to the controller approvals endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ publication: {}, approval: {} }),
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })

    await operationsApi.decidePublication('project id', 'publication/id', 'RED', 'Not ready')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/publications/publication%2Fid/approvals')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ decision: 'RED', reason: 'Not ready' })
  })

  it('uses the project membership endpoint and preserves the backend role code', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ({}) })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })

    await operationsApi.createMembership('p', {
      userUuid: 'u',
      role: 'GORUNTULEYICI',
      startsAt: null,
      endsAt: null,
    })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/p/memberships')
    expect(JSON.parse(String(init.body)).role).toBe('GORUNTULEYICI')
  })
})
