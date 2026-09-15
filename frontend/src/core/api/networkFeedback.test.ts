import { afterEach, expect, it, vi } from 'vitest'
import { apiRequest } from './client'
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
