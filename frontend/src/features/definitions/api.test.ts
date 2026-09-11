import { afterEach, describe, expect, it, vi } from 'vitest'
import { definitionsApi } from './api'

afterEach(() => vi.unstubAllGlobals())

function mockResponse(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body })
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('crypto', { randomUUID: () => 'correlation-id' })
  return fetchMock
}

describe('definition organization API contracts', () => {
  it('creates a nested folder through the project-scoped endpoint', async () => {
    const fetchMock = mockResponse({ uuid: 'folder-1' })
    const input = { parentUuid: 'parent/1', type: 'GELISTIRME' as const, code: 'FINANCE', name: 'Finance', description: '' }

    await definitionsApi.createFolder('project id', input)

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/folders')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual(input)
  })

  it('moves a definition with an optimistic version precondition', async () => {
    const fetchMock = mockResponse({ uuid: 'definition-1' })

    await definitionsApi.moveDefinition('project', 'definition/id', { folderUuid: 'folder-2', expectedVersion: 7 })

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project/definitions/definition%2Fid/move')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ folderUuid: 'folder-2', expectedVersion: 7 })
  })
})
