import { ApiError, errorOf, expireSession, getSession } from './client'

/** One subscription of `POST /console/stream`, as sent (snake_case, see docs/CONSOLE.md). */
export type WireSubscription = { id: string; kind: 'metric' | 'logs' | 'ws-events' | 'status' } & Record<string, unknown>

/** `snapshot`/`update` data frames; `error` frames carry `code` and `message` instead. */
export interface DataFrame {
  id: string
  mode: 'replace' | 'append'
  data: unknown
}

export interface ErrorFrame {
  id: string
  code: string
  message: string
}

export interface OpenStream {
  /** The token the stream was opened with: `end{token-expired}` expires exactly that session. */
  token: string | undefined
  body: ReadableStream<Uint8Array>
}

/**
 * Opens the push stream. The JWT goes in the `Authorization` header, never in the URL, so this is
 * `fetch`, not `EventSource`. A refusal before the stream starts (400, 401, 403, 429) is thrown as
 * the backend's `ApiError`; a 401 also expires the session, as for every other call.
 */
export async function openStream(subscriptions: WireSubscription[], signal: AbortSignal): Promise<OpenStream> {
  const token = getSession()?.token
  const headers = new Headers({ Accept: 'text/event-stream', 'Content-Type': 'application/json' })
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch('/api/console/stream', { method: 'POST', headers, body: JSON.stringify({ subscriptions }), signal })
  if (response.status === 401) expireSession(token)
  if (!response.ok) throw await errorOf(response)
  if (!response.body) throw new ApiError(0, 'NETWORK_ERROR', 'The stream has no body')
  return { token, body: response.body }
}
