import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { WireSubscription } from '@/api/stream'
import type { ConnectionHandlers } from './connection'
import { INITIAL, type Spec, StreamRegistry, canonical } from './registry'

type Conn = { subs: WireSubscription[]; h: ConnectionHandlers; close: ReturnType<typeof vi.fn> }

function setup() {
  const conns: Conn[] = []
  const connect = vi.fn((subs: WireSubscription[], h: ConnectionHandlers) => {
    const close = vi.fn()
    conns.push({ subs, h, close })
    return close
  })
  const registry = new StreamRegistry({ connect, debounceMs: 250 })
  const last = () => conns.at(-1) as Conn
  const idOf = (spec: Spec) => last().subs.find((s) => canonical({ ...s, id: undefined }) === canonical(spec))?.id as string
  return { registry, connect, conns, last, idOf }
}

const status: Spec = { kind: 'status' }
const logs = (extra: Record<string, unknown> = {}): Spec => ({ kind: 'logs', range: '15m', limit: 3, ...extra })
const line = (n: number) => ({ time: `2026-10-18T12:00:0${n}Z`, level: 'info', service: 'backend', message: `m${n}`, request_id: null })
const noop = () => undefined

describe('canonical', () => {
  it('ignoresKeyOrderAndUndefinedParams', () => {
    expect(canonical({ kind: 'logs', range: '1h', service: undefined, limit: 5 })).toBe(canonical({ limit: 5, range: '1h', kind: 'logs' }))
    expect(canonical({ kind: 'metric', filters: { b: '1', a: '2' } })).toBe(canonical({ kind: 'metric', filters: { a: '2', b: '1' } }))
    expect(canonical(logs())).not.toBe(canonical(logs({ level: 'warn' })))
  })
})

describe('StreamRegistry', () => {
  beforeEach(() => vi.useFakeTimers({ now: Date.UTC(2026, 9, 18, 12, 0, 0) }))
  afterEach(() => vi.useRealTimers())

  it('debouncesSubscriptionChangesIntoOneStreamWithOneIdPerCanonicalParams', () => {
    const { registry, connect, last } = setup()
    registry.subscribe(canonical(status), noop)
    registry.subscribe(canonical(logs()), noop)
    registry.subscribe(canonical({ ...logs(), service: undefined }), noop)
    vi.advanceTimersByTime(249)
    expect(connect).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(connect).toHaveBeenCalledTimes(1)
    expect(last().subs).toHaveLength(2)
    expect(last().subs.map((s) => s.kind).sort()).toEqual(['logs', 'status'])
    expect(last().subs.every((s) => /^[A-Za-z0-9_.-]{1,64}$/.test(s.id))).toBe(true)
    expect(last().subs.find((s) => s.kind === 'logs')).toMatchObject({ range: '15m', limit: 3 })
  })

  it('refCountsAndReopensOnlyWhenTheSetChanges', () => {
    const { registry, connect, conns } = setup()
    const a = registry.subscribe(canonical(status), noop)
    const b = registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    a()
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(1)

    // Unmount and remount within the debounce (StrictMode, lazy groups): nothing changes.
    b()
    const c = registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(1)

    registry.subscribe(canonical(logs()), noop)
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(2)
    expect(conns[0].close).toHaveBeenCalledTimes(1)
    expect(conns[1].subs).toHaveLength(2)

    c()
    vi.advanceTimersByTime(250)
    expect(conns[2].subs.map((s) => s.kind)).toEqual(['logs'])
  })

  it('closesTheStreamWhenNothingIsSubscribedAndForgetsTheData', () => {
    const { registry, conns, last, idOf } = setup()
    const off = registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { dev: true } })
    off()
    vi.advanceTimersByTime(250)
    expect(conns[0].close).toHaveBeenCalled()
    expect(registry.getStatus().state).toBe('idle')
    expect(registry.get(canonical(status))).toBe(INITIAL)
  })

  it('replacesOnSnapshotAndReplaceFramesAndNotifiesListeners', () => {
    const { registry, last, idOf } = setup()
    const listener = vi.fn()
    registry.subscribe(canonical(status), listener)
    vi.advanceTimersByTime(250)
    const key = canonical(status)
    expect(registry.get(key)).toEqual({ received: false, stale: false })

    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    expect(registry.get(key)).toEqual({ data: { n: 1 }, received: true, stale: false })
    last().h.onData('update', { id: idOf(status), mode: 'replace', data: { n: 2 } })
    expect(registry.get(key).data).toEqual({ n: 2 })
    expect(listener).toHaveBeenCalledTimes(2)
    // Frames for ids it does not hold are ignored.
    last().h.onData('update', { id: 'nobody', mode: 'replace', data: {} })
    expect(listener).toHaveBeenCalledTimes(2)
    expect(registry.getStatus().updatedAt).toBe(Date.now())
  })

  it('logsSnapshotIsNewestFirstAppendsArePrependedReversedAndCappedToTheLimit', () => {
    const { registry, last, idOf } = setup()
    const key = canonical(logs())
    registry.subscribe(key, noop)
    vi.advanceTimersByTime(250)
    const id = idOf(logs())
    last().h.onData('snapshot', { id, mode: 'append', data: { lines: [line(2), line(1)], total: 2 } })
    expect((registry.get(key).data as { message: string }[]).map((l) => l.message)).toEqual(['m2', 'm1'])
    expect(registry.get(key).data).toContainEqual({ time: '2026-10-18T12:00:01Z', level: 'info', service: 'backend', message: 'm1', requestId: null })

    // Appends come oldest first.
    last().h.onData('update', { id, mode: 'append', data: { lines: [line(3), line(4)], total: 2 } })
    expect((registry.get(key).data as { message: string }[]).map((l) => l.message)).toEqual(['m4', 'm3', 'm2'])

    // A reconnect's snapshot replaces: no duplicates.
    last().h.onData('snapshot', { id, mode: 'append', data: { lines: [line(4), line(3)], total: 2 } })
    expect((registry.get(key).data as { message: string }[]).map((l) => l.message)).toEqual(['m4', 'm3'])
  })

  it('wsEventsAreCappedAt200NewestFirst', () => {
    const { registry, last, idOf } = setup()
    const spec: Spec = { kind: 'ws-events', feed: 'orders' }
    registry.subscribe(canonical(spec), noop)
    vi.advanceTimersByTime(250)
    const ev = (n: number) => ({ at: String(n), kind: 'push', detail: 'd' })
    last().h.onData('snapshot', { id: idOf(spec), mode: 'append', data: { feed: '/ws/v1/orders', events: Array.from({ length: 200 }, (_, i) => ev(200 - i)) } })
    last().h.onData('update', { id: idOf(spec), mode: 'append', data: { feed: '/ws/v1/orders', events: [ev(201)] } })
    const events = registry.get(canonical(spec)).data as { at: string }[]
    expect(events).toHaveLength(200)
    expect(events[0].at).toBe('201')
    expect(events.at(-1)?.at).toBe('2')
  })

  it('logsWithoutALimitUseTheBackendDefaultOf100', () => {
    const { registry, last, idOf } = setup()
    const spec: Spec = { kind: 'logs', range: '1h' }
    registry.subscribe(canonical(spec), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf(spec), mode: 'append', data: { lines: Array.from({ length: 120 }, () => line(1)), total: 120 } })
    expect(registry.get(canonical(spec)).data).toHaveLength(100)
  })

  it('errorFramesAreIsolatedPerSubscriptionAndClearedByTheNextData', () => {
    const { registry, last, idOf } = setup()
    registry.subscribe(canonical(status), noop)
    registry.subscribe(canonical(logs()), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    last().h.onError({ id: idOf(status), code: 'SOURCE_UNAVAILABLE', message: 'down' })
    last().h.onError({ id: 'nobody', code: 'X', message: 'x' })
    const state = registry.get(canonical(status))
    expect(state.error).toBeInstanceOf(ApiError)
    expect(state).toMatchObject({ data: { n: 1 }, error: { code: 'SOURCE_UNAVAILABLE', message: 'down' } })
    expect(registry.get(canonical(logs())).error).toBeUndefined()

    last().h.onData('update', { id: idOf(status), mode: 'replace', data: { n: 2 } })
    expect(registry.get(canonical(status)).error).toBeUndefined()
  })

  it('reportsConnectionStatesAndMarksDataStaleWhileReconnecting', () => {
    const { registry, last, idOf } = setup()
    const onStatus = vi.fn()
    registry.onStatus(onStatus)
    registry.subscribe(canonical(status), noop)
    registry.subscribe(canonical(logs()), noop)
    vi.advanceTimersByTime(250)
    expect(registry.getStatus().state).toBe('connecting')
    last().h.onOpen()
    expect(registry.getStatus().state).toBe('connected')
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })

    const limit = new ApiError(429, 'STREAM_LIMIT', 'too many')
    last().h.onRetry(limit, 1000)
    expect(registry.getStatus()).toMatchObject({ state: 'reconnecting', error: limit })
    expect(registry.get(canonical(status))).toMatchObject({ stale: true, data: { n: 1 } })
    expect(registry.get(canonical(status)).error).toBeUndefined()
    // A subscription that never had data shows why instead of loading for ever.
    expect(registry.get(canonical(logs())).error).toBe(limit)

    last().h.onOpen()
    expect(registry.getStatus()).toEqual({ state: 'connected', updatedAt: Date.now() })
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    expect(registry.get(canonical(status)).stale).toBe(false)
    expect(onStatus).toHaveBeenCalled()
  })

  it('aStoppedStreamShowsItsErrorOnEverySubscriptionWithoutData', () => {
    const { registry, last, idOf } = setup()
    registry.subscribe(canonical(status), noop)
    registry.subscribe(canonical(logs()), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    const bad = new ApiError(400, 'INVALID_SUBSCRIPTION', 'bad')
    last().h.onStop(bad)
    expect(registry.getStatus()).toMatchObject({ state: 'stopped', error: bad })
    expect(registry.get(canonical(logs())).error).toBe(bad)
    expect(registry.get(canonical(status)).error).toBeUndefined()
  })

  it('pausingClosesTheStreamKeepsTheDataAndResumingReopensWithTheFullSet', () => {
    const { registry, connect, conns, last, idOf } = setup()
    registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    last().h.onOpen()
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    last().h.onRetry(new ApiError(0, 'NETWORK_ERROR', 'x'), 1000)

    registry.setLive(false)
    expect(conns[0].close).toHaveBeenCalled()
    expect(registry.getStatus().state).toBe('paused')
    expect(registry.get(canonical(status))).toMatchObject({ data: { n: 1 }, stale: false })
    // Frames of the closed stream that were already on their way are dropped.
    conns[0].h.onData('update', { id: idOf(status), mode: 'replace', data: { n: 9 } })
    expect(registry.get(canonical(status)).data).toEqual({ n: 1 })

    registry.setLive(false)
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(1)
    registry.setLive(true)
    expect(connect).toHaveBeenCalledTimes(2)
    expect(registry.getStatus().state).toBe('reconnecting')
    expect(conns[1].subs).toEqual(conns[0].subs)
  })

  it('whilePausedANewSubscriptionGetsOneSnapshotThenTheStreamClosesAgain', () => {
    const { registry, connect, conns, last, idOf } = setup()
    registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 1 } })
    registry.setLive(false)
    expect(conns[0].close).toHaveBeenCalled()

    // The range changed: the new subscription has nothing to show, so the stream opens once.
    registry.subscribe(canonical(logs()), noop)
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(2)
    last().h.onOpen()
    expect(registry.getStatus().state).toBe('paused')
    last().h.onData('snapshot', { id: idOf(status), mode: 'replace', data: { n: 2 } })
    expect(conns[1].close).not.toHaveBeenCalled()
    last().h.onError({ id: idOf(logs()), code: 'LOG_STORE_UNAVAILABLE', message: 'down' })
    expect(conns[1].close).toHaveBeenCalled()
    expect(registry.getStatus().state).toBe('paused')

    // One failed try is the answer while paused: the error shows, nothing retries.
    registry.subscribe(canonical(logs({ level: 'warn' })), noop)
    vi.advanceTimersByTime(250)
    last().h.onRetry(new ApiError(0, 'NETWORK_ERROR', 'x'), 1000)
    expect(conns[2].close).toHaveBeenCalled()
    expect(registry.getStatus()).toMatchObject({ state: 'paused', error: { code: 'NETWORK_ERROR' } })
    expect(registry.get(canonical(logs({ level: 'warn' }))).error).toMatchObject({ code: 'NETWORK_ERROR' })

    registry.subscribe(canonical({ kind: 'ws-events', feed: 'orders' }), noop)
    vi.advanceTimersByTime(250)
    last().h.onData('snapshot', { id: idOf({ kind: 'ws-events', feed: 'orders' }), mode: 'append', data: { feed: 'x', events: [] } })
    expect(conns[3].close).toHaveBeenCalled()
    expect(registry.get(canonical(status))).toMatchObject({ data: { n: 2 }, stale: false })
  })

  it('pausedWithNothingSubscribedIsIdle', () => {
    const { registry } = setup()
    registry.setLive(false)
    expect(registry.getStatus().state).toBe('idle')
  })

  it('suspendClosesTheStreamAndALaterFlushReopensIt', () => {
    const { registry, connect, conns } = setup()
    registry.subscribe(canonical(status), noop)
    vi.advanceTimersByTime(250)
    registry.suspend()
    expect(conns[0].close).toHaveBeenCalled()
    registry.setLive(true)
    vi.advanceTimersByTime(250)
    expect(connect).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('givesDistinctIdsEvenIfTwoKeysHashAlike', () => {
    const { registry, last } = setup()
    const specs = Array.from({ length: 300 }, (_, i): Spec => ({ kind: 'logs', range: '1h', text: `t${i}` }))
    for (const s of specs) registry.subscribe(canonical(s), noop)
    vi.advanceTimersByTime(250)
    expect(new Set(last().subs.map((s) => s.id)).size).toBe(300)
  })
})
