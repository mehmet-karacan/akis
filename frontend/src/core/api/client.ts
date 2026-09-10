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

export function setAuthorizationHeader(value: string | null) {
  authorizationHeader = value
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Correlation-Id', crypto.randomUUID())
  if (authorizationHeader) headers.set('Authorization', authorizationHeader)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  const response = await fetch(path, { ...init, headers })
  if (!response.ok) {
    let problem: ProblemDetails = { status: response.status, title: response.statusText }
    try {
      problem = (await response.json()) as ProblemDetails
    } catch {
      // A network intermediary may return a non-JSON response.
    }
    throw new ApiProblem(problem, response.status)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function jsonBody(value: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(value) }
}
