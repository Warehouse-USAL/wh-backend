import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession, onUnauthorized, setSession } from '@/api/client'
import type { WireSubscription } from '@/api/stream'
import { jsonResponse } from '@/test/http'
import { fakeStream } from '@/test/stream'
import { type ConnectionHandlers, backoff, connect } from './connection'

const subs: WireSubscription[] = [{ id: 's', kind: 'status' }]

function handlers() {
  return {
    onOpen: vi.fn(),
    onData: vi.fn(),
    onError: vi.fn(),
    onRetry: vi.fn(),
    onStop: vi.fn(),
  } satisfies ConnectionHandlers
}

const settle = () => vi.advanceTimersByTimeAsync(0)

describe('backoff', () => {
  it('doublesFromOneSecondWithJitterAndCapsAtThirtySeconds', () => {
    expect(backoff(0, () => 0)).toBe(500)
    expect(backoff(0, () => 1)).toBe(1000)
    expect(backoff(3, () => 1)).toBe(8000)
    expect(backoff(4, () => 0.5)).toBe(12_000)
    expect(backoff(10, () => 1)).toBe(30_000)
    expect(backoff(50, () => 0)).toBe(15_000)
  })
})

describe('connect', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(1)
    setSession({ token: 'tok', email: 'root@x.io' })
  })
  afterEach(() => vi.useRealTimers())

  it('postsTheSubscriptionsWithTheTokenInTheHeaderAndDeliversFrames', async () => {
    const stream = fakeStream()
    const h = handlers()
    connect(subs, h)
    await settle()

    const [url, init] = stream.fetch.mock.calls[0]
    expect(url).toBe('/api/console/stream')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer tok')
    expect(JSON.parse(String(init?.body))).toEqual({ subscriptions: subs })
    expect(h.onOpen).toHaveBeenCalledTimes(1)

    stream.last().write(': ping\n\nevent: snapshot\ndata: {"id":"s","mode":"replace","data":{"dev":true}}\n\n')
    stream.last().write('event: mystery\ndata: {}\n\nevent: update\ndata: not json\n\n')
    stream.last().error(() => true, 'SOURCE_UNAVAILABLE', 'down')
    await settle()
    expect(h.onData).toHaveBeenCalledTimes(1)
    expect(h.onData).toHaveBeenCalledWith('snapshot', { id: 's', mode: 'replace', data: { dev: true } })
    expect(h.onError).toHaveBeenCalledWith({ id: 's', code: 'SOURCE_UNAVAILABLE', message: 'down' })
  })

  it('reconnectsWithExponentialBackoffResettingAfterAnOpen', async () => {
    const stream = fakeStream()
    stream.fetch.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const h = handlers()
    connect(subs, h)
    await settle()
    expect(h.onRetry).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'NETWORK_ERROR', message: 'Failed to fetch' }), 1000)

    await vi.advanceTimersByTimeAsync(999)
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(stream.fetch).toHaveBeenCalledTimes(2)
    expect(h.onRetry).toHaveBeenLastCalledWith(expect.anything(), 2000)

    await vi.advanceTimersByTimeAsync(2000)
    expect(stream.fetch).toHaveBeenCalledTimes(3)
    expect(h.onOpen).toHaveBeenCalledTimes(1)

    // A dropped stream after a good open starts over at one second.
    stream.last().close()
    await settle()
    expect(h.onRetry).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'STREAM_CLOSED' }), 1000)
    await vi.advanceTimersByTimeAsync(1000)
    expect(stream.fetch).toHaveBeenCalledTimes(4)
  })

  it('anEndOtherThanTokenExpiredReconnectsAndTheServerSendsFreshSnapshots', async () => {
    const stream = fakeStream()
    const h = handlers()
    connect(subs, h)
    await settle()
    stream.last().end('server-shutdown')
    await settle()
    expect(h.onRetry).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_ENDED', message: expect.stringContaining('server-shutdown') }), 1000)
    await vi.advanceTimersByTimeAsync(1000)
    expect(stream.opens).toHaveLength(2)
    // Every reconnect re-sends the full set.
    expect(stream.opens[1].subscriptions).toEqual(subs)
    expect(h.onStop).not.toHaveBeenCalled()
  })

  it('tokenExpiredEndsTheSessionLikeA401AndStops', async () => {
    const stream = fakeStream()
    const expired = vi.fn()
    const off = onUnauthorized(expired)
    const h = handlers()
    connect(subs, h)
    await settle()
    stream.last().end('token-expired')
    await settle()
    expect(getSession()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(h.onStop).toHaveBeenCalledWith(expect.objectContaining({ status: 401, code: 'TOKEN_EXPIRED' }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    off()
  })

  it('a401OnOpenExpiresTheSessionAndStops', async () => {
    const expired = vi.fn()
    const off = onUnauthorized(expired)
    const stream = fakeStream({ refuse: () => jsonResponse(401, { error: { code: 'UNAUTHORIZED', message: 'no' } }) })
    const h = handlers()
    connect(subs, h)
    await settle()
    expect(getSession()).toBeNull()
    expect(expired).toHaveBeenCalledTimes(1)
    expect(h.onStop).toHaveBeenCalledWith(expect.objectContaining({ status: 401 }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    off()
  })

  it('a400IsSurfacedAndNotRetried', async () => {
    const stream = fakeStream({ refuse: () => jsonResponse(400, { error: { code: 'INVALID_SUBSCRIPTION', message: 'bad' } }) })
    const h = handlers()
    connect(subs, h)
    await settle()
    expect(h.onStop).toHaveBeenCalledWith(expect.objectContaining({ status: 400, code: 'INVALID_SUBSCRIPTION', message: 'bad' }))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    expect(getSession()).not.toBeNull()
  })

  it('a429StreamLimitIsSurfacedAndRetriedWithBackoff', async () => {
    let refusals = 2
    const stream = fakeStream({
      refuse: () => (refusals-- > 0 ? jsonResponse(429, { error: { code: 'STREAM_LIMIT', message: 'too many' } }) : undefined),
    })
    const h = handlers()
    connect(subs, h)
    await settle()
    expect(h.onRetry).toHaveBeenLastCalledWith(expect.objectContaining({ status: 429, code: 'STREAM_LIMIT' }), 1000)
    await vi.advanceTimersByTimeAsync(1000)
    expect(h.onRetry).toHaveBeenLastCalledWith(expect.objectContaining({ code: 'STREAM_LIMIT' }), 2000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(h.onOpen).toHaveBeenCalledTimes(1)
    expect(stream.fetch).toHaveBeenCalledTimes(3)
  })

  it('theWatchdogReconnectsAfter45SecondsWithoutAByteButPingsKeepItAlive', async () => {
    const stream = fakeStream()
    const h = handlers()
    connect(subs, h)
    await settle()
    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(15_000)
      stream.last().write(': ping\n\n')
    }
    await settle()
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    expect(h.onRetry).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(45_000)
    expect(stream.opens[0].signal.aborted).toBe(true)
    expect(h.onRetry).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_STALLED' }), 1000)
    await vi.advanceTimersByTimeAsync(1000)
    expect(stream.fetch).toHaveBeenCalledTimes(2)
  })

  it('theWatchdogAlsoCoversAnOpenThatNeverAnswers', async () => {
    const stream = fakeStream()
    stream.fetch.mockImplementationOnce((_, init) => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))))
    const h = handlers()
    connect(subs, h)
    await vi.advanceTimersByTimeAsync(45_000)
    expect(h.onRetry).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_STALLED' }), 1000)
  })

  it('closeAbortsTheStreamAndCancelsAPendingRetry', async () => {
    const stream = fakeStream()
    const h = handlers()
    const close = connect(subs, h)
    await settle()
    close()
    await settle()
    expect(stream.opens[0].signal.aborted).toBe(true)
    expect(h.onRetry).not.toHaveBeenCalled()

    stream.fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const again = connect(subs, h)
    await settle()
    expect(h.onRetry).toHaveBeenCalledTimes(1)
    again()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(stream.fetch).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('aStreamWithoutABodyIsRetried', async () => {
    fakeStream().fetch.mockResolvedValueOnce(new Response(null, { status: 200 }))
    const h = handlers()
    connect(subs, h)
    await settle()
    expect(h.onRetry).toHaveBeenCalledWith(expect.objectContaining({ code: 'NETWORK_ERROR' }), 1000)
  })
})
