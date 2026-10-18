import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { type MetricQuery, clearMetricAnswers } from '@/api/metrics'
import { jsonResponse } from '@/test/http'
import { PALETTE } from '@/widgets/scale'
import { LiveProvider, useLive } from './live'
import { stepFor, useMetric, windowFor } from './useMetric'

const hits: MetricQuery = { metric: 'http.server.duration', agg: 'increase', groupBy: ['status_class'] }

const body = (v: number) => ({
  metric: 'http.server.duration',
  unit: 'ms',
  step: '15s',
  series: [{ labels: { status_class: '2xx' }, points: [[1000, v]] }],
})

function useBoth(req: MetricQuery | MetricQuery[]) {
  return { metric: useMetric(req), live: useLive() }
}

const wrapper = ({ children }: { children: ReactNode }) => <LiveProvider>{children}</LiveProvider>

const sentBodies = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

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
    // Every instant inside one step bucket yields the same window, so ticks do not shift the buckets.
    expect(windowFor('15m', now + 7000)).toEqual(windowFor('15m', now))
    expect(windowFor('1h', Date.UTC(2026, 9, 18, 12, 0, 59)).to).toBe('2026-10-18T12:00:00.000Z')
  })
})

describe('useMetric', () => {
  // 14 s into a 15 s bucket: the first tick (+5 s) moves `to`, the second (+10 s) does not.
  beforeEach(() => {
    clearMetricAnswers()
    vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.UTC(2026, 9, 18, 12, 0, 14) })
  })
  afterEach(() => vi.useRealTimers())

  it('useMetricRefetchesOnTick', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, body(fetchMock.mock.calls.length)))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useBoth(hits), { wrapper })

    await waitFor(() => expect(result.current.metric.loading).toBe(false))
    expect(result.current.metric.series).toEqual([{ label: '2xx', color: PALETTE.blue, points: [{ t: 1_000_000, v: 1 }] }])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/metrics/query')

    expect(result.current.metric.times).toHaveLength(60)

    act(() => void vi.advanceTimersByTime(5000))
    expect(result.current.metric).toMatchObject({ loading: false, refreshing: true })
    await waitFor(() => expect(result.current.metric.series[0].points[0].v).toBe(2))
    expect(result.current.metric.refreshing).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    // Same `to` as the previous tick: the kept answer is reused, no request.
    act(() => void vi.advanceTimersByTime(5000))
    await waitFor(() => expect(result.current.metric.refreshing).toBe(false))
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('identicalHooksShareOnePost', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, body(1)))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => [useMetric(hits), useMetric({ ...hits }), useMetric([hits])], { wrapper })
    await waitFor(() => expect(result.current.every((m) => !m.loading)).toBe(true))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(result.current.map((m) => m.series[0].points[0].v)).toEqual([1, 1, 1])
  })

  it('doesNotRefetchWhileNotLiveButDoesOnRangeChange', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, body(1)))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useBoth(hits), { wrapper })
    await waitFor(() => expect(result.current.metric.loading).toBe(false))

    act(() => result.current.live.toggle())
    act(() => void vi.advanceTimersByTime(30000))
    await act(async () => {})
    expect(fetchMock).toHaveBeenCalledTimes(1)

    act(() => result.current.live.setRange('6h'))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(sentBodies(fetchMock)[1].step).toBe('5m')
    await waitFor(() => expect(result.current.metric.loading).toBe(false))
  })

  it('aRequestEqualByValueDoesNotRefetch', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, body(1)))
    vi.stubGlobal('fetch', fetchMock)
    // A fresh object on every render, as a page writes it inline.
    const { result, rerender } = renderHook(({ route }) => useMetric({ ...hits, filters: { route } }), {
      wrapper,
      initialProps: { route: '/products' },
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    rerender({ route: '/products' })
    rerender({ route: '/products' })
    await act(async () => {})
    expect(fetchMock).toHaveBeenCalledTimes(1)

    rerender({ route: '/orders' })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(sentBodies(fetchMock)[1].filters).toEqual({ route: '/orders' })
  })

  it('keepsThePreviousSeriesWhileReloadingAndIgnoresStaleResponses', async () => {
    const pending: ((r: Response) => void)[] = []
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(
      (_, init) =>
        new Promise((resolve, reject) => {
          pending.push(resolve)
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { result, rerender } = renderHook(({ route }) => useMetric({ ...hits, filters: { route } }), {
      wrapper,
      initialProps: { route: '/a' },
    })
    expect(result.current).toEqual({ series: [], times: [], loading: true, refreshing: false })
    await act(async () => pending[0](jsonResponse(200, body(1))))
    expect(result.current.series[0].points[0].v).toBe(1)

    rerender({ route: '/b' })
    expect(result.current.loading).toBe(true)
    expect(result.current.series[0].points[0].v).toBe(1)
    rerender({ route: '/c' })
    await act(async () => {})
    // The request for /b was aborted (nobody else wanted it); even if its response lands, it is ignored.
    expect((fetchMock.mock.calls[1][1] as RequestInit).signal?.aborted).toBe(true)
    await act(async () => pending[1](jsonResponse(200, body(2))))
    expect(result.current).toMatchObject({ loading: true })
    expect(result.current.series[0].points[0].v).toBe(1)

    await act(async () => pending[2](jsonResponse(200, body(3))))
    expect(result.current).toMatchObject({ loading: false, refreshing: false, series: [expect.objectContaining({ points: [{ t: 1_000_000, v: 3 }] })] })
  })

  it('apiErrorSurfacesInTheHook', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>()
        .mockResolvedValueOnce(jsonResponse(200, body(1)))
        .mockResolvedValueOnce(jsonResponse(503, { error: { code: 'METRICS_UNAVAILABLE', message: 'down' } }))
        .mockResolvedValueOnce(jsonResponse(200, body(4))),
    )
    const { result } = renderHook(() => useBoth(hits), { wrapper })
    await waitFor(() => expect(result.current.metric.loading).toBe(false))

    act(() => void vi.advanceTimersByTime(5000))
    await waitFor(() => expect(result.current.metric.error).toBeInstanceOf(ApiError))
    expect(result.current.metric).toMatchObject({ loading: false, error: { status: 503, code: 'METRICS_UNAVAILABLE' } })
    // The last good series stay on screen next to the error.
    expect(result.current.metric.series[0].points[0].v).toBe(1)

    act(() => void vi.advanceTimersByTime(5000))
    await waitFor(() => expect(result.current.metric.error).toBeUndefined())
    expect(result.current.metric.series[0].points[0].v).toBe(4)
  })

  it('aNetworkFailureBecomesAnApiError', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))
    const { result } = renderHook(() => useMetric(hits), { wrapper })
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(result.current.error).toMatchObject({ status: 0, code: 'NETWORK_ERROR', message: 'Failed to fetch' })
    expect(result.current.error).toBeInstanceOf(ApiError)
  })

  it('aListOfQueriesYieldsTheirSeriesInOrder', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_, init) => {
      const { agg } = JSON.parse((init as RequestInit).body as string)
      return jsonResponse(200, { metric: 'm', unit: 'ms', step: '15s', series: [{ labels: {}, points: [[1, agg === 'p50' ? 5 : 9]] }] })
    })
    vi.stubGlobal('fetch', fetchMock)
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
})
