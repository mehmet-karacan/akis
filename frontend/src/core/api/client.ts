import { recordChangeFor, recordChangedEvent } from './recordChanges'

export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
  correlationId?: string
}

export class ApiProblem extends Error {
  readonly status: number
  readonly code?: string
  readonly correlationId?: string

  constructor(problem: ProblemDetails, status: number) {
    super(problem.detail ?? problem.title ?? `Request failed (${status})`)
    this.name = 'ApiProblem'
    this.status = problem.status ?? status
    this.code = problem.code
    this.correlationId = problem.correlationId
  }
}

let authorizationHeader: string | null = null
export const unauthorizedEvent = 'akis:unauthorized'

export function setAuthorizationHeader(value: string | null) {
  authorizationHeader = value
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Correlation-Id', crypto.randomUUID())
  if (authorizationHeader) headers.set('Authorization', authorizationHeader)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    if (init.signal?.aborted || (typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError')) throw error
    throw networkError()
  }
  if (!response.ok) {
    let problem: ProblemDetails = { status: response.status, title: response.statusText }
    let parsed = false
    try {
      problem = (await response.json()) as ProblemDetails
      parsed = true
    } catch {
      // A network intermediary may return a non-JSON response.
    }
    if ([502, 503, 504].includes(response.status) && (!parsed || (!problem.detail && !problem.code))) {
      throw networkError()
    }
    if (response.status === 401) {
      authorizationHeader = null
      sessionStorage.removeItem('akis.localSession')
      // Let provider listeners finish mounting when the first page request is
      // the request that discovers an expired development credential.
      setTimeout(() => window.dispatchEvent(new Event(unauthorizedEvent)), 0)
      if (window.location.pathname !== '/login') window.location.replace('/login?reason=expired')
    }
    throw new ApiProblem(problem, response.status)
  }
  const change = recordChangeFor(path, init.method ?? 'GET')
  if (change) window.dispatchEvent(new CustomEvent(recordChangedEvent, { detail: change }))
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function jsonBody(value: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(value) }
}
import { notifyNetworkFailure } from './networkFeedback'
import i18n from '../i18n'

function networkError() {
  notifyNetworkFailure()
  return new Error(i18n.language.startsWith('tr')
    ? 'Uygulama sunucusuna ulaşılamıyor. Bağlantınızı kontrol edip yeniden deneyin.'
    : 'Cannot reach the application server. Check your connection and try again.')
}
