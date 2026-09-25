import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiProblem } from '../../core/api/client'
import { createIdempotencyKey, executionApi, isExecutionDisabled } from './api'

afterEach(() => vi.unstubAllGlobals())

function fetchResponse(body: unknown = {}) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
  })
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('crypto', { randomUUID: () => 'client-generated-uuid' })
  return fetchMock
}

describe('execution API contracts', () => {
  it('starts a run with the publication body and client idempotency key', async () => {
    const fetchMock = fetchResponse({ runUuid: 'run-id' })

    await executionApi.startRun('project id', 'publication/id', 'request-key-123')

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/v1/projects/project%20id/runs')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('request-key-123')
    expect(JSON.parse(String(init.body))).toEqual({ publicationUuid: 'publication/id' })
  })

  it('uses the run-specific cancel, event, and typed step endpoints', async () => {
    const fetchMock = fetchResponse([])

    await executionApi.listEvents('project', 'run/id')
    await executionApi.listEventPage('project', 'run/id', 100, 50)
    await executionApi.listSteps('project', 'run/id')
    await executionApi.cancelRun('project', 'run/id')

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project/runs/run%2Fid/events')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/projects/project/runs/run%2Fid/events/search?after=100&size=50')
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/v1/projects/project/runs/run%2Fid/steps')
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/projects/project/runs/run%2Fid/cancel')
    expect((fetchMock.mock.calls[3]?.[1] as RequestInit).method).toBe('POST')
  })

  it('loads the project capability contract before enabling runtime actions', async () => {
    const fetchMock = fetchResponse({ contractVersion: 1 })
    await executionApi.getCapabilities('project/id')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project%2Fid/capabilities')
  })

  it('sends run catalog filters and real pagination to the server', async () => {
    const fetchMock = fetchResponse({ items: [], total: 0, page: 2, size: 50 })
    await executionApi.searchRuns('project/id', { view: 'ACTIVE', query: 'load', page: 2, size: 50 })
    const path = String(fetchMock.mock.calls[0]?.[0])
    expect(path).toContain('/api/v1/projects/project%2Fid/runs/search?')
    expect(path).toContain('view=ACTIVE')
    expect(path).toContain('query=load')
    expect(path).toContain('page=2')
  })

  it('loads the project operations overview', async () => {
    const fetchMock = fetchResponse({ totalRuns: 0, selectedRowsExact: '0' })
    await executionApi.getOverview('project/id')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/projects/project%2Fid/runs/overview')
  })

  it('recognizes only the explicit disabled-execution problem and generates the key client-side', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'client-generated-uuid' })
    const disabled = new ApiProblem({
      status: 503,
      code: 'EXECUTION_REQUESTS_DISABLED',
      detail: 'disabled',
    }, 503)
    const unrelated = new ApiProblem({ status: 503, code: 'OTHER' }, 503)

    expect(isExecutionDisabled(disabled)).toBe(true)
    expect(isExecutionDisabled(unrelated)).toBe(false)
    expect(createIdempotencyKey()).toBe('client-generated-uuid')
  })
})
