import { afterEach, describe, expect, it, vi } from 'vitest'

describe('api client CSRF contract', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('uses the browser cookie first and refreshes with the server token after 403', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: 'csrf rejected' }), { status: 403 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'server-token', headerName: 'X-XSRF-TOKEN' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    vi.resetModules()
    const { apiRequest } = await import('./client')
    await apiRequest<{ ok: boolean }>('/api/v1/auth/login', { method: 'POST', body: '{}' })

    const requests = fetchMock.mock.calls.filter((call) => call[1])
    const mutationRequest = requests.at(-1)?.[1] as RequestInit
    const headers = new Headers(mutationRequest.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('server-token')
    expect(headers.get('X-XSRF-TOKEN')).not.toBe('test-csrf-token')
  })
})
