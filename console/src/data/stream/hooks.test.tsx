import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { setSession } from '@/api/client'
import { useLogs, useStatus, useWsEvents } from '@/data/streamHooks'
import { StreamWrapper, byKind, fakeStream } from '@/test/stream'
import { useLive } from '../live'
import { useLogsIn } from './hooks'
import { useStreamStatus, useSubscription } from './useStream'

const wrapper = StreamWrapper
const wire = (n: number, extra = {}) => ({ time: `2026-10-18T12:00:0${n}Z`, level: 'info', service: 'backend', message: `m${n}`, request_id: `r${n}`, ...extra })

describe('stream hooks', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))

  it('useStatusMapsThePayloadAndReplacesIt', async () => {
    const stream = fakeStream()
    const { result } = renderHook(useStatus, { wrapper })
    expect(result.current).toEqual({ data: { services: [], dev: false }, loading: true })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    expect(stream.last().subscriptions).toEqual([{ id: expect.any(String), kind: 'status' }])

    act(() =>
      stream.last().send(byKind('status'), { dev: true, services: [{ name: 'backend', state: 'UP', detail: null, uptime_ms: 5 }] }, 'snapshot'),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual({ dev: true, services: [{ name: 'backend', state: 'UP', detail: null, uptimeMs: 5 }] })

    act(() => stream.last().send(byKind('status'), { dev: false, services: [{ name: 'bridge', state: 'DOWN', detail: 'unreachable', uptime_ms: null }] }))
    await waitFor(() => expect(result.current.data.dev).toBe(false))
    expect(result.current.data.services).toEqual([{ name: 'bridge', state: 'DOWN', detail: 'unreachable', uptimeMs: null }])
  })

  it('useLogsFollowsTheShellRangeAndPrependsAppendsCappedToTheLimit', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => ({ logs: useLogs({ service: 'backend', limit: 3 }), live: useLive() }), { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    expect(stream.last().subscriptions[0]).toEqual({ id: expect.any(String), kind: 'logs', service: 'backend', limit: 3, range: '15m' })

    act(() => stream.last().send(byKind('logs'), { lines: [wire(2), wire(1)], total: 2 }, 'snapshot'))
    await waitFor(() => expect(result.current.logs.loading).toBe(false))
    expect(result.current.logs.data[0]).toEqual({ time: '2026-10-18T12:00:02Z', level: 'info', service: 'backend', message: 'm2', requestId: 'r2' })

    act(() => stream.last().send(byKind('logs'), { lines: [wire(3), wire(4)], total: 2 }))
    await waitFor(() => expect(result.current.logs.data.map((l) => l.message)).toEqual(['m4', 'm3', 'm2']))

    act(() => result.current.live.setRange('1h'))
    await waitFor(() => expect(stream.opens).toHaveLength(2))
    expect(stream.last().subscriptions[0]).toMatchObject({ range: '1h' })
    expect(result.current.logs).toEqual({ data: [], loading: true })
  })

  it('useLogsInWithNullSubscribesToNothing', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => [useLogsIn(null, '24h'), useStatus()], { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    expect(stream.last().subscriptions.map((s) => s.kind)).toEqual(['status'])
    expect(result.current[0]).toEqual({ data: [], loading: true })
  })

  it('useWsEventsPrependsEachPushedEvent', async () => {
    const stream = fakeStream({ answer: () => ({ data: { feed: '/ws/v1/vehicles', events: [{ at: '1', kind: 'connect', detail: 'a' }] } }) })
    const { result } = renderHook(() => useWsEvents('vehicles'), { wrapper })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(stream.last().subscriptions[0]).toMatchObject({ kind: 'ws-events', feed: 'vehicles' })
    act(() => stream.last().send(byKind('ws-events'), { feed: '/ws/v1/vehicles', events: [{ at: '2', kind: 'push', detail: 'b' }] }))
    await waitFor(() => expect(result.current.data.map((e) => e.at)).toEqual(['2', '1']))
  })

  it('anErrorFrameIsTheHooksErrorNotAnEmptyList', async () => {
    fakeStream({ answer: () => ({ error: { code: 'LOG_STORE_UNAVAILABLE', message: 'down' } }) })
    const { result } = renderHook(() => useLogs({}), { wrapper })
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(result.current).toMatchObject({ data: [], loading: false, error: { code: 'LOG_STORE_UNAVAILABLE' } })
  })

  it('useStreamStatusTracksTheConnectionAndTheLastFrame', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => ({ status: useStreamStatus(), live: useLive(), sub: useSubscription({ kind: 'status' }) }), { wrapper })
    expect(result.current.status.state).toBe('idle')
    await waitFor(() => expect(result.current.status.state).toBe('connected'))
    act(() => stream.last().send(byKind('status'), { dev: false, services: [] }, 'snapshot'))
    await waitFor(() => expect(result.current.status.updatedAt).toBeDefined())
    act(() => result.current.live.toggle())
    await waitFor(() => expect(result.current.status.state).toBe('paused'))
    act(() => result.current.live.toggle())
    await waitFor(() => expect(result.current.status.state).toBe('connected'))
    expect(stream.fetch).toHaveBeenCalledTimes(2)
  })

  it('useStreamStatusIsIdleOutsideAProvider', () => {
    const { result } = renderHook(useStreamStatus)
    expect(result.current).toEqual({ state: 'idle' })
  })

  it('subscriptionHooksFailClearlyOutsideAProvider', () => {
    expect(() => renderHook(useStatus)).toThrow('Stream hooks must be used inside a StreamProvider')
  })
})
