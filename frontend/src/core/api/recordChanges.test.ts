import { afterEach, expect, it, vi } from 'vitest'
import { apiRequest } from './client'
import { recordChangeFor, recordChangedEvent } from './recordChanges'

afterEach(() => vi.unstubAllGlobals())
it('recognizes record creation, version edits and nested folder moves', () => {
  for (const path of ['/api/v1/projects/p%20id/connections', '/api/v2/projects/p%20id/connections/id/versions'])
    expect(recordChangeFor(path, 'POST')).toEqual({ projectUuid: 'p id', kind: 'connections' })
  expect(recordChangeFor('/api/v1/projects/p/models/m/data-objects', 'POST')).toEqual({ projectUuid: 'p', kind: 'data-objects' })
  expect(recordChangeFor('/api/v1/projects/p/models/m/data-objects/o/folder', 'PATCH')).toEqual({ projectUuid: 'p', kind: 'data-objects' })
  expect(recordChangeFor('/api/v1/projects/p/definitions/d/draft', 'PUT')).toEqual({ projectUuid: 'p', kind: 'definitions' })
})
it('ignores tests, validations, non-record endpoints and malformed identifiers', () => {
  for (const path of ['/api/v1/projects/p/connections/c/tests', '/api/v1/projects/p/connections/c/versions/v/test',
    '/api/v1/projects/p/definitions/d/value-tests', '/api/v1/projects/p/models/m/import',
    '/api/v1/projects/p/models/m/submodels', '/api/v1/projects/p/record-audit/connections',
    '/api/v1/projects/%/models', 'https://example.com/api/v1/projects/p/models']) expect(recordChangeFor(path, 'POST')).toBeNull()
  expect(recordChangeFor('/api/v1/projects/p/models', 'GET')).toBeNull()
})
it('invalidates after successful writes including no-content responses, never on a conflict', async () => {
  const listener = vi.fn()
  window.addEventListener(recordChangedEvent, listener)
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce({ ok: true, status: 204 })
    .mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ detail: 'Conflict' }) }))
  try {
    await apiRequest('/api/v1/projects/p/models/m', { method: 'PATCH' })
    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener.mock.calls[0]![0].detail).toEqual({ projectUuid: 'p', kind: 'models' })
    await expect(apiRequest('/api/v1/projects/p/models/m', { method: 'PATCH' })).rejects.toThrow('Conflict')
    expect(listener).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener(recordChangedEvent, listener) }
})
