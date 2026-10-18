/** An HTTP failure, carrying the backend's `{"error":{"code","message"}}` when it sent one. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export interface Session {
  token: string
  email: string
}

const STORAGE_KEY = 'wh-console.session'

// sessionStorage can throw (privacy modes, blocked storage); the in-memory copy is the source of truth.
function readStored(): Session | null {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null')
    return isSession(value) ? value : null
  } catch {
    return null
  }
}

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false
  const { token, email } = value as Record<string, unknown>
  return typeof token === 'string' && typeof email === 'string'
}

let session = readStored()
const listeners = new Set<() => void>()
const unauthorizedHandlers = new Set<() => void>()

export function getSession(): Session | null {
  return session
}

export function setSession(next: Session | null): void {
  session = next
  try {
    if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage unavailable: the session lives in memory only.
  }
  listeners.forEach((listener) => listener())
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Runs `handler` whenever a request made with a session gets a 401 (the session is cleared first). */
export function onUnauthorized(handler: () => void): () => void {
  unauthorizedHandlers.add(handler)
  return () => unauthorizedHandlers.delete(handler)
}

/** Calls the backend under `/api`, returning the parsed body (`null` when empty). */
export async function api(path: string, init: RequestInit = {}): Promise<unknown> {
  const token = session?.token
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (typeof init.body === 'string' && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  const response = await fetch(`/api${path}`, { ...init, headers })
  // Only the session that sent the request is expired; a sign-in made meanwhile stays.
  if (response.status === 401 && token && session?.token === token) {
    setSession(null)
    unauthorizedHandlers.forEach((handler) => handler())
  }
  const body = parse(await response.text())
  if (!response.ok) throw toApiError(response, body)
  return body
}

function parse(text: string): unknown {
  if (text === '') return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function toApiError(response: Response, body: unknown): ApiError {
  const error = (body as { error?: { code?: unknown; message?: unknown } } | null)?.error
  if (typeof error?.code === 'string' && typeof error.message === 'string') {
    return new ApiError(response.status, error.code, error.message)
  }
  return new ApiError(response.status, `HTTP_${response.status}`, response.statusText || `Request failed (${response.status})`)
}
