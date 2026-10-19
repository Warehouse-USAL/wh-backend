import { type ReactNode, createElement } from 'react'
import { vi } from 'vitest'
import type { WireSubscription } from '@/api/stream'
import { LiveProvider } from '@/data/live'
import { StreamProvider } from '@/data/stream/StreamProvider'
import { jsonResponse } from './http'

/** What a fake backend source answers for a subscription: data (snapshot), an error frame, or nothing yet. */
export type Answer = { data: unknown } | { error: { code: string; message: string } } | undefined

/** One open `POST /console/stream` of the fake backend. */
export interface FakeConnection {
  subscriptions: WireSubscription[]
  signal: AbortSignal
  /** Writes raw text (any split, any line ends) to the response body. */
  write(text: string): void
  /** Writes one frame; `data` frames take the subscription's mode from its kind. */
  frame(event: string, payload: unknown): void
  /** `snapshot`/`update` for the first subscription matching `match`. */
  send(match: Match, data: unknown, event?: 'snapshot' | 'update'): void
  error(match: Match, code: string, message: string): void
  end(reason: string): void
  /** Ends the body without an end frame (a dropped connection). */
  close(): void
  sub(match: Match): WireSubscription
}

type Match = (s: WireSubscription) => boolean

const MODE: Record<WireSubscription['kind'], 'replace' | 'append'> = { metric: 'replace', status: 'replace', logs: 'append', 'ws-events': 'append' }

export interface FakeStream {
  fetch: ReturnType<typeof vi.fn<typeof fetch>>
  /** Every stream opened so far, oldest first. */
  opens: FakeConnection[]
  last(): FakeConnection
  /** Streams whose client has not aborted them. */
  open(): FakeConnection[]
}

/**
 * Replaces `fetch` with a fake push backend. Each `POST /api/console/stream` gets an open SSE body
 * that the test writes to; `answer` (when given) sends every subscription its snapshot as soon as
 * the stream opens, as the real server does. `refuse` answers an open with an error response
 * instead; `other` answers any other request.
 */
export function fakeStream(
  options: {
    answer?: (s: WireSubscription) => Answer
    refuse?: (subscriptions: WireSubscription[]) => Response | undefined
    other?: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response> | Response
  } = {},
): FakeStream {
  const opens: FakeConnection[] = []
  const encoder = new TextEncoder()
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    if (String(input) !== '/api/console/stream') return options.other ? options.other(input, init) : jsonResponse(404, { error: { code: 'NOT_FOUND', message: String(input) } })
    const subscriptions = (JSON.parse(String(init?.body)) as { subscriptions: WireSubscription[] }).subscriptions
    const refused = options.refuse?.(subscriptions)
    if (refused) return refused
    let controller!: ReadableStreamDefaultController<Uint8Array>
    let done = false
    const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) })
    const signal = init?.signal as AbortSignal
    signal.addEventListener('abort', () => {
      if (done) return
      done = true
      controller.error(new DOMException('The operation was aborted.', 'AbortError'))
    })
    const conn: FakeConnection = {
      subscriptions,
      signal,
      write: (text) => void (!done && controller.enqueue(encoder.encode(text))),
      frame: (event, payload) => conn.write(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`),
      sub: (match) => {
        const found = subscriptions.find(match)
        if (!found) throw new Error(`no subscription matches in ${JSON.stringify(subscriptions)}`)
        return found
      },
      send: (match, data, event = 'update') => {
        const s = conn.sub(match)
        conn.frame(event, { id: s.id, mode: MODE[s.kind], data })
      },
      error: (match, code, message) => conn.frame('error', { id: conn.sub(match).id, code, message }),
      end: (reason) => {
        conn.frame('end', { reason })
        conn.close()
      },
      close: () => {
        if (done) return
        done = true
        controller.close()
      },
    }
    opens.push(conn)
    if (options.answer) {
      for (const s of subscriptions) {
        const a = options.answer(s)
        if (a && 'data' in a) conn.send((x) => x.id === s.id, a.data, 'snapshot')
        else if (a) conn.error((x) => x.id === s.id, a.error.code, a.error.message)
      }
    }
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  return {
    fetch: fetchMock,
    opens,
    last: () => {
      const conn = opens.at(-1)
      if (!conn) throw new Error('no stream was opened')
      return conn
    },
    open: () => opens.filter((c) => !c.signal.aborted),
  }
}

/** The subscriptions of every stream request, in order. */
export const streamRequests = (stream: FakeStream) => stream.opens.map((c) => c.subscriptions)

export const byKind = (kind: WireSubscription['kind']) => (s: WireSubscription) => s.kind === kind

/** The shell's providers: Live (range, toggle) and the push stream. */
export function StreamWrapper({ children }: { children: ReactNode }) {
  return createElement(LiveProvider, null, createElement(StreamProvider, null, children))
}
