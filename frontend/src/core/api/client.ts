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

export const unauthorizedEvent = 'akis:unauthorized'
let csrfToken: string | null = null
let csrfHeaderName = 'X-XSRF-TOKEN'

export async function refreshCsrfToken() {
  const response = await fetch('/api/v1/auth/csrf', {
    headers: { Accept: 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
    credentials: 'same-origin',
  })
  if (!response.ok) throw new ApiProblem({ status: response.status, title: response.statusText }, response.status)
  const payload = await response.json() as { token: string; headerName: string }
  csrfToken = payload.token
  csrfHeaderName = payload.headerName
  // Spring's deferred CSRF token is intentionally different from the raw
  // XSRF-TOKEN cookie. Keep the server-issued cookie untouched; overwriting it
  // with the response token makes Spring reject the next mutation with 403.
}

export async function ensureCsrfToken() {
  if (csrfToken) return
  // Use the browser-visible repository cookie for the first attempt. Spring
  // may rotate/defer the authoritative token; apiRequest handles that case
  // by refreshing once after the server returns 403.
  const cookieToken = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  if (cookieToken) {
    csrfToken = decodeURIComponent(cookieToken)
    csrfHeaderName = 'X-XSRF-TOKEN'
    return
  }
  await refreshCsrfToken()
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const mutating = !['GET', 'HEAD', 'OPTIONS'].includes(method)
  if (mutating) await ensureCsrfToken()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Correlation-Id', crypto.randomUUID())
  if (csrfToken && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(csrfHeaderName, csrfToken)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  let response: Response
  try {
    response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  } catch (error) {
    if (init.signal?.aborted || (typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError')) throw error
    throw networkError()
  }
  // A browser can retain the in-memory token while the server has rotated the
  // CSRF cookie (for example after a restart or an expired session). Spring
  // rejects that request with a plain 403 before the controller is reached.
  // Refresh once and retry only the rejected mutating request; successful
  // mutations are never replayed because the first response is specifically
  // a CSRF rejection.
  if (response.status === 403 && mutating) {
    csrfToken = null
    await refreshCsrfToken()
    if (csrfToken) headers.set(csrfHeaderName, csrfToken)
    response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
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
      // Let provider listeners finish mounting when the first page request is
      // the request that discovers an expired session.
      setTimeout(() => window.dispatchEvent(new Event(unauthorizedEvent)), 0)
      if (!path.startsWith('/api/v1/auth/') && window.location.pathname !== '/login') window.location.replace('/login?reason=expired')
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
