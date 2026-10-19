import { ApiError, errorText, expireSession } from '@/api/client'
import { type DataFrame, type ErrorFrame, type WireSubscription, openStream } from '@/api/stream'
import { createSseDecoder } from './sse'

/** Twice the server's 15 s heartbeat plus slack: no byte for this long means the stream is dead. */
export const WATCHDOG_MS = 45_000
const BASE_MS = 1000
const CAP_MS = 30_000

/** Exponential backoff with "equal jitter": attempt n waits between half and all of min(30 s, 1 s * 2^n). */
export function backoff(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(CAP_MS, BASE_MS * 2 ** attempt)
  return ceiling / 2 + random() * (ceiling / 2)
}

export interface ConnectionHandlers {
  /** The backend accepted the subscriptions; each one's snapshot follows. */
  onOpen(): void
  onData(event: 'snapshot' | 'update', frame: DataFrame): void
  onError(frame: ErrorFrame): void
  /** The stream is down and reopens in `delayMs`; `error` says why. */
  onRetry(error: ApiError, delayMs: number): void
  /** Down for good: the request itself was refused (400, 403) or the session ended (401, token expired). */
  onStop(error: ApiError): void
}

const stalled = () => new ApiError(0, 'STREAM_STALLED', `No data for ${WATCHDOG_MS / 1000} s`)

/**
 * Keeps one push stream open for `subscriptions` until the returned function is called: every
 * (re)connect sends the full set and the server answers each with a fresh snapshot. A dropped
 * connection, a network error, a 5xx, a 429 or an `end` other than `token-expired` reopen it after
 * {@link backoff} (reset by every successful open); a watchdog reopens it when no byte (frame or
 * `: ping`) arrived for {@link WATCHDOG_MS}.
 */
export function connect(subscriptions: WireSubscription[], h: ConnectionHandlers): () => void {
  let closed = false
  let attempt = 0
  let controller: AbortController | undefined
  let watchdog: ReturnType<typeof setTimeout> | undefined
  let retry: ReturnType<typeof setTimeout> | undefined
  let stall = false

  const arm = () => {
    clearTimeout(watchdog)
    watchdog = setTimeout(() => {
      stall = true
      controller?.abort()
    }, WATCHDOG_MS)
  }

  async function run() {
    controller = new AbortController()
    stall = false
    let token: string | undefined
    let ended: string | undefined
    let error: ApiError
    try {
      arm()
      const opened = await openStream(subscriptions, controller.signal)
      token = opened.token
      attempt = 0
      h.onOpen()
      const decoder = createSseDecoder(({ event, data }) => {
        let frame: unknown
        try {
          frame = JSON.parse(data)
        } catch {
          return
        }
        if (event === 'snapshot' || event === 'update') h.onData(event, frame as DataFrame)
        else if (event === 'error') h.onError(frame as ErrorFrame)
        else if (event === 'end') ended = String((frame as { reason?: unknown }).reason)
      })
      const reader = opened.body.getReader()
      while (ended === undefined) {
        const { done, value } = await reader.read()
        if (done) break
        arm()
        decoder.push(value)
      }
      if (ended !== undefined) void reader.cancel().catch(() => undefined)
      error = ended === undefined ? new ApiError(0, 'STREAM_CLOSED', 'The stream closed') : new ApiError(0, 'STREAM_ENDED', `The stream ended (${ended})`)
    } catch (e) {
      error = stall ? stalled() : e instanceof ApiError ? e : new ApiError(0, 'NETWORK_ERROR', errorText(e))
    } finally {
      clearTimeout(watchdog)
    }
    if (closed) return
    if (ended === 'token-expired') {
      expireSession(token)
      return h.onStop(new ApiError(401, 'TOKEN_EXPIRED', 'Your session expired'))
    }
    if (error.status === 400 || error.status === 401 || error.status === 403) return h.onStop(error)
    const delay = backoff(attempt++)
    h.onRetry(error, delay)
    retry = setTimeout(() => void run(), delay)
  }

  void run()
  return () => {
    closed = true
    clearTimeout(retry)
    clearTimeout(watchdog)
    controller?.abort()
  }
}
