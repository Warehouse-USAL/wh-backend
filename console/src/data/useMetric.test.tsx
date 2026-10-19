import { act, renderHook, waitFor } from '@testing-library/react'
import { StrictMode, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, setSession } from '@/api/client'
import type { MetricQuery } from '@/api/metrics'
import { PALETTE } from '@/widgets/scale'
import { StreamWrapper, byKind, fakeStream } from '@/test/stream'
import { useLive } from './live'
import { stepFor, timesOf, useMetric, windowFor } from './useMetric'

const hits: MetricQuery = { metric: 'http.server.duration', agg: 'increase', groupBy: ['status_class'] }
const WINDOW = { from: '2026-10-18T11:45:15Z', to: '2026-10-18T12:00:00Z', step: '15s' }

const frame = (v: number, window = WINDOW) => ({
  metric: 'http.server.duration',
  unit: 'ms',
  step: '15s',
  series: [{ labels: { status_class: '2xx' }, points: [[1000, v]] }],
  window,
})

function useBoth(req: MetricQuery | MetricQuery[]) {
  return { metric: useMetric(req), live: useLive() }
}

const wrapper = StreamWrapper
const metric = byKind('metric')

describe('stepFor', () => {
  it('stepForKeepsPointsUnderTheCap', () => {
    const seconds = { s: 1, m: 60, h: 3600 }
    const span = (d: string) => Number(d.slice(0, -1)) * seconds[d.slice(-1) as 's' | 'm' | 'h']
    for (const range of ['5m', '15m', '1h', '6h', '24h'] as const) {
      const step = stepFor(range)
      expect(step).toMatch(/^\d{1,6}[smh]$/)
      expect(span(step)).toBeGreaterThanOrEqual(10)
      expect(span(range) / span(step)).toBeLessThanOrEqual(11000)
    }
    expect(['5m', '15m', '1h', '6h', '24h'].map((r) => stepFor(r as never))).toEqual(['10s', '15s', '1m', '5m', '15m'])
  })

  it('alignsTheWindowEndToTheStep', () => {
    const now = Date.UTC(2026, 9, 18, 12, 0, 7, 500)
    const w = windowFor('15m', now)
    // One bucket per step, each ending at its time: the first covers 11:45:00-11:45:15.
    expect(w).toMatchObject({ from: '2026-10-18T11:45:15.000Z', to: '2026-10-18T12:00:00.000Z', step: '15s' })
    expect(w.times).toHaveLength(60)
    expect(w.times[0]).toBe(Date.parse(w.from))
    expect(w.times.at(-1)).toBe(Date.parse(w.to))
    expect(w.times[1] - w.times[0]).toBe(15_000)
    expect(windowFor('15m', now + 7000)).toEqual(windowFor('15m', now))
    expect(windowFor('1h', Date.UTC(2026, 9, 18, 12, 0, 59)).to).toBe('2026-10-18T12:00:00.000Z')
    // The server's window (what the stream sends) gives the same grid.
    expect(timesOf(WINDOW)).toEqual(w.times)
  })
})

describe('useMetric', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))
  afterEach(() => vi.useRealTimers())

  it('subscribesWithTheCanonicalParamsOfTheShellRange', async () => {
    const stream = fakeStream()
    renderHook(() => useMetric({ metric: 'm', agg: 'p95', filters: { route: '/a' }, groupBy: ['route', 'method'], label: 'x' }), { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    const [{ id, ...sub }] = stream.last().subscriptions
    expect(id).toMatch(/^[A-Za-z0-9_.-]{1,64}$/)
    // The label is the client's; it is not part of what the server computes.
    expect(sub).toEqual({ kind: 'metric', metric: 'm', agg: 'p95', range: '15m', step: '15s', filters: { route: '/a' }, group_by: ['method', 'route'] })
  })

  it('aFixedLookbackIgnoresTheShellRangeAndTakesItsOwnStep', async () => {
    const stream = fakeStream()
    renderHook(() => useMetric({ metric: 'job.run', agg: 'last' }, '30d'), { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    expect(stream.last().subscriptions[0]).toMatchObject({ range: '30d', step: '1d' })
    expect([stepFor('2d'), stepFor('7d')]).toEqual(['1m', '1h'])
    expect(windowFor('2d', Date.UTC(2026, 9, 18)).times).toHaveLength(2880)
  })

  it('useMetricUpdatesWhenAFrameArrivesWithoutAnyRequest', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => useBoth(hits), { wrapper })
    expect(result.current.metric).toEqual({ series: [], times: [], loading: true, refreshing: false })
    await waitFor(() => expect(stream.opens).toHaveLength(1))

    act(() => stream.last().send(metric, frame(1), 'snapshot'))
    await waitFor(() => expect(result.current.metric.loading).toBe(false))
    expect(result.current.metric.series).toEqual([{ label: '2xx', color: PALETTE.blue, points: [{ t: 1_000_000, v: 1 }] }])
    expect(result.current.metric.times).toHaveLength(60)

    act(() => stream.last().send(metric, frame(2)))
    await waitFor(() => expect(result.current.metric.series[0].points[0].v).toBe(2))
    expect(stream.fetch).toHaveBeenCalledTimes(1)
  })

  it('theBucketGridIsTheServersWindowNotTheClientClock', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    // A client clock minutes off: the grid still follows the frame.
    const shifted = { from: '2026-10-18T09:00:15Z', to: '2026-10-18T09:15:00Z', step: '15s' }
    act(() => stream.last().send(metric, frame(1, shifted), 'snapshot'))
    await waitFor(() => expect(result.current.times[0]).toBe(Date.parse(shifted.from)))
    expect(result.current.times.at(-1)).toBe(Date.parse(shifted.to))
  })

  it('makesNoPeriodicRequestWhileLive', async () => {
    vi.useFakeTimers({ now: Date.UTC(2026, 9, 18, 12, 0, 0) })
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await act(() => vi.advanceTimersByTimeAsync(250))
    expect(result.current.loading).toBe(false)
    // Five minutes live, the server pinging every 15 s as it does: still the one stream request.
    for (let i = 0; i < 20; i++) {
      await act(() => vi.advanceTimersByTimeAsync(15_000))
      stream.last().write(': ping\n\n')
    }
    expect(stream.fetch).toHaveBeenCalledTimes(1)
  })

  it('identicalHooksShareOneSubscription', async () => {
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    const { result } = renderHook(() => [useMetric(hits), useMetric({ ...hits }), useMetric([hits])], { wrapper })
    await waitFor(() => expect(result.current.every((m) => !m.loading)).toBe(true))
    expect(stream.fetch).toHaveBeenCalledTimes(1)
    expect(stream.last().subscriptions).toHaveLength(1)
    expect(result.current.map((m) => m.series[0].points[0].v)).toEqual([1, 1, 1])
  })

  it('opensOneStreamUnderStrictMode', async () => {
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    const strict = ({ children }: { children: ReactNode }) => (
      <StrictMode>
        <StreamWrapper>{children}</StreamWrapper>
      </StrictMode>
    )
    const { result } = renderHook(() => useMetric(hits), { wrapper: strict })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(stream.fetch).toHaveBeenCalledTimes(1)
  })

  it('pausingClosesTheStreamAndKeepsTheSeriesAndARangeChangeGetsItsSnapshot', async () => {
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    const { result } = renderHook(() => useBoth(hits), { wrapper })
    await waitFor(() => expect(result.current.metric.loading).toBe(false))

    act(() => result.current.live.toggle())
    await waitFor(() => expect(stream.opens[0].signal.aborted).toBe(true))
    expect(result.current.metric).toMatchObject({ loading: false, refreshing: false, series: [expect.objectContaining({ label: '2xx' })] })

    act(() => result.current.live.setRange('6h'))
    await waitFor(() => expect(stream.fetch).toHaveBeenCalledTimes(2))
    expect(stream.opens[1].subscriptions[0]).toMatchObject({ range: '6h', step: '5m' })
    await waitFor(() => expect(result.current.metric.loading).toBe(false))
    // Paused: it closes again once the snapshot is in.
    await waitFor(() => expect(stream.opens[1].signal.aborted).toBe(true))

    act(() => result.current.live.toggle())
    await waitFor(() => expect(stream.fetch).toHaveBeenCalledTimes(3))
  })

  it('aRequestEqualByValueDoesNotResubscribe', async () => {
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    // A fresh object on every render, as a page writes it inline.
    const { result, rerender } = renderHook(({ route }) => useMetric({ ...hits, filters: { route } }), {
      wrapper,
      initialProps: { route: '/products' },
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    rerender({ route: '/products' })
    rerender({ route: '/products' })
    await act(() => new Promise((r) => setTimeout(r, 300)))
    expect(stream.fetch).toHaveBeenCalledTimes(1)

    rerender({ route: '/orders' })
    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(stream.fetch).toHaveBeenCalledTimes(2))
    expect(stream.opens[1].subscriptions.map((s) => s.filters)).toEqual([{ route: '/orders' }])
    expect(stream.opens[0].signal.aborted).toBe(true)
  })

  it('keepsTheSeriesWhileTheStreamReconnectsAndMarksThemRefreshing', async () => {
    const stream = fakeStream()
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(stream.opens).toHaveLength(1))
    act(() => stream.last().send(metric, frame(1), 'snapshot'))
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => stream.last().close())
    await waitFor(() => expect(result.current.refreshing).toBe(true))
    expect(result.current.series[0].points[0].v).toBe(1)
    await waitFor(() => expect(stream.opens).toHaveLength(2), { timeout: 2000 })
    act(() => stream.last().send(metric, frame(3), 'snapshot'))
    await waitFor(() => expect(result.current).toMatchObject({ loading: false, refreshing: false }))
    expect(result.current.series[0].points[0].v).toBe(3)
  })

  it('apiErrorSurfacesInTheHook', async () => {
    const stream = fakeStream({ answer: () => ({ data: frame(1) }) })
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => stream.last().error(metric, 'METRICS_UNAVAILABLE', 'down'))
    await waitFor(() => expect(result.current.error).toBeInstanceOf(ApiError))
    expect(result.current).toMatchObject({ loading: false, error: { code: 'METRICS_UNAVAILABLE', message: 'down' } })
    // The last good series stay on screen next to the error.
    expect(result.current.series[0].points[0].v).toBe(1)

    act(() => stream.last().send(metric, frame(4)))
    await waitFor(() => expect(result.current.error).toBeUndefined())
    expect(result.current.series[0].points[0].v).toBe(4)
  })

  it('anErrorStaysWithItsOwnSubscription', async () => {
    const stream = fakeStream({ answer: (s) => (s.agg === 'p95' ? { error: { code: 'UNKNOWN_METRIC', message: 'La suscripción no es válida.' } } : { data: frame(1) }) })
    const { result } = renderHook(() => [useMetric(hits), useMetric({ ...hits, agg: 'p95' })], { wrapper })
    await waitFor(() => expect(result.current.every((m) => !m.loading)).toBe(true))
    expect(result.current[0].error).toBeUndefined()
    expect(result.current[0].series).toHaveLength(1)
    expect(result.current[1]).toMatchObject({ series: [], error: { code: 'UNKNOWN_METRIC' } })
    expect(stream.fetch).toHaveBeenCalledTimes(1)
  })

  it('aNetworkFailureBecomesAnApiError', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(result.current.error).toMatchObject({ status: 0, code: 'NETWORK_ERROR', message: 'Failed to fetch' })
    expect(result.current.error).toBeInstanceOf(ApiError)
    expect(result.current.loading).toBe(false)
  })

  it('aListOfQueriesYieldsTheirSeriesInOrder', async () => {
    fakeStream({
      answer: (s) => ({ data: { metric: 'm', unit: 'ms', step: '15s', window: WINDOW, series: [{ labels: {}, points: [[1, s.agg === 'p50' ? 5 : 9]] }] } }),
    })
    const queries: MetricQuery[] = [
      { metric: 'http.server.duration', agg: 'p50', label: 'p50' },
      { metric: 'http.server.duration', agg: 'p99', label: 'p99' },
    ]
    const { result } = renderHook(() => useMetric(queries), { wrapper })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.series.map((s) => [s.label, s.points[0].v])).toEqual([
      ['p50', 5],
      ['p99', 9],
    ])
  })

  it('aFrameWithoutAWindowHasNoGrid', async () => {
    fakeStream({ answer: () => ({ data: { metric: 'm', unit: 'ms', step: '15s', series: [] } }) })
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current).toEqual({ series: [], times: [], loading: false, refreshing: false })
  })

  it('failsClearlyOutsideAStreamProvider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    expect(() => renderHook(() => useMetric(hits), { wrapper: ({ children }) => <StreamWrapper>{children}</StreamWrapper> })).not.toThrow()
    expect(() => renderHook(() => useMetric(hits))).toThrow()
  })
})
