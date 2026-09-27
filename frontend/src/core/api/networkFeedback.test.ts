import { afterEach, expect, it, vi } from 'vitest'
import { ApiProblem, apiRequest } from './client'
import { NETWORK_FAILURE_EVENT } from './networkFeedback'

afterEach(() => vi.unstubAllGlobals())
it('notifies on failed fetch and replaces the raw browser error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  const listener = vi.fn()
  window.addEventListener(NETWORK_FAILURE_EVENT, listener)
  try {
    await expect(apiRequest('/api/test')).rejects.not.toThrow('Failed to fetch')
    expect(listener).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener(NETWORK_FAILURE_EVENT, listener) }
})
it('does not notify for an intentional cancellation', async () => {
  const error = new DOMException('Cancelled', 'AbortError')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error))
  const listener = vi.fn()
  window.addEventListener(NETWORK_FAILURE_EVENT, listener)
  try {
    await expect(apiRequest('/api/test')).rejects.toBe(error)
    expect(listener).not.toHaveBeenCalled()
  } finally { window.removeEventListener(NETWORK_FAILURE_EVENT, listener) }
})
it('notifies when the proxy cannot reach the backend', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 502, ok: false }))
  await expect(apiRequest('/api/test')).rejects.toThrow()
})
it('preserves an application problem returned with service unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    status: 503,
    ok: false,
    statusText: 'Service Unavailable',
    json: vi.fn().mockResolvedValue({
      status: 503,
      code: 'ORACLE_CREDENTIAL_UNAVAILABLE',
      detail: 'Oracle kimlik secret değeri çalışma ortamında bulunamadı.',
    }),
}))
  await expect(apiRequest('/api/test')).rejects.toEqual(expect.objectContaining({
    name: ApiProblem.name,
    code: 'ORACLE_CREDENTIAL_UNAVAILABLE',
    message: 'Oracle kimlik secret değeri çalışma ortamında bulunamadı.',
  }))
})

it('refreshes the csrf token and retries a rejected mutation once', async () => {
  let mutationAttempts = 0
  let csrfRefreshes = 0
  const request = vi.fn().mockImplementation((url: string) => {
    if (url === '/api/v1/auth/csrf') {
      csrfRefreshes += 1
      return Promise.resolve({
        status: 200,
        ok: true,
        json: vi.fn().mockResolvedValue({ token: `fresh-token-${csrfRefreshes}`, headerName: 'X-XSRF-TOKEN' }),
      })
    }
    mutationAttempts += 1
    return Promise.resolve(mutationAttempts === 1
      ? { status: 403, ok: false, statusText: 'Forbidden' }
      : { status: 200, ok: true, json: vi.fn().mockResolvedValue({ ok: true }) })
  })
  vi.stubGlobal('fetch', request)

  // The first call obtains the stale token; the retry path must fetch a fresh
  // token before replaying the rejected mutation.
  await expect(apiRequest('/api/test', { method: 'POST', body: '{}' })).resolves.toEqual({ ok: true })
  expect(mutationAttempts).toBe(2)
  expect(csrfRefreshes).toBeGreaterThanOrEqual(1)
  expect(document.cookie).not.toContain('XSRF-TOKEN=fresh-token-')
})
