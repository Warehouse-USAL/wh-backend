import { useMemo } from 'react'
import type { ApiError } from '@/api/client'
import { type MetricQuery, type MetricsQueryResponse, type TimeWindow, stepMs, toSeries } from '@/api/metrics'
import type { Series } from '@/widgets/scale'
import { type Range, useLive } from './live'
import type { Spec } from './stream/registry'
import { useSubscriptions } from './stream/useStream'

const STEP: Record<Range, string> = { '5m': '10s', '15m': '15s', '1h': '1m', '6h': '5m', '24h': '15m' }
export const RANGE_MS: Record<Range, number> = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 }

/**
 * 20-100 buckets per range, well under the backend's 11000 points and above its 10 s minimum.
 * At 5m the 10 s step equals the backend's OTel export interval (TELEMETRY_EXPORT_INTERVAL_MS),
 * so a bucket can catch zero or two exports and short-range counters look jittery; that is the
 * data, not the chart.
 */
export function stepFor(range: Range): string {
  return STEP[range]
}

/**
 * The window ends at `now` rounded down to the step (the stream's metric source computes the same
 * window server side and sends it with each frame), and its buckets (`times`, epoch ms) are the
 * points VictoriaMetrics answers for: `from`, `from + step`, ... `to`, one per step of the range,
 * each covering the step that ends there. Aligned, every frame and every query of one range share
 * the same bucket times; widgets lay series onto `times`, so a bucket no series has stays an
 * empty slot (a gap) instead of being squeezed out of the axis. Nothing is ever zero-filled.
 *
 * Freshness: the newest bucket lags real time by up to one step (the floor) plus VictoriaMetrics'
 * `-search.latencyOffset` (30 s by default), within which it does not answer recent samples.
 */
export function windowFor(range: Range, now: number): TimeWindow & { times: number[] } {
  const step = stepFor(range)
  const ms = stepMs(step)
  const to = Math.floor(now / ms) * ms
  const from = to - RANGE_MS[range] + ms
  const times = Array.from({ length: RANGE_MS[range] / ms }, (_, i) => from + i * ms)
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString(), step, times }
}

export interface MetricState {
  series: Series[]
  /** The window's bucket times for the series shown; pass to the widgets' `times`. */
  times: number[]
  /** Until the first frame (data or error) of every subscription of this request and range. */
  loading: boolean
  /** The stream is reopening; the previous series stay on screen until the fresh snapshot. */
  refreshing: boolean
  error?: ApiError
}

/** The stream's params for one query over `range`: the step is {@link stepFor}'s. */
export function metricSpec({ metric, agg, filters = {}, groupBy = [] }: MetricQuery, range: Range): Spec {
  return { kind: 'metric', metric, agg, range, step: stepFor(range), filters, group_by: [...groupBy].sort() }
}

/** Bucket times (epoch ms) of a frame's window: `from`, `from + step`, ... `to`. */
export function timesOf({ from, to, step }: TimeWindow): number[] {
  const ms = stepMs(step)
  const start = Date.parse(from)
  return Array.from({ length: Math.floor((Date.parse(to) - start) / ms) + 1 }, (_, i) => start + i * ms)
}

/**
 * Series for one query or several (their series concatenated in order), pushed by the stream: one
 * subscription per query and range, shared with every other widget asking the same. Each frame
 * replaces the series and carries the server's window, so `times` is exactly its bucket grid.
 * Nothing is fetched here and nothing ticks.
 */
export function useMetric(req: MetricQuery | MetricQuery[]): MetricState {
  const { range } = useLive()
  const json = JSON.stringify(Array.isArray(req) ? req : [req])
  const queries = useMemo(() => JSON.parse(json) as MetricQuery[], [json])
  const specs = useMemo(() => queries.map((q) => metricSpec(q, range)), [queries, range])
  const states = useSubscriptions(specs)
  return useMemo(() => {
    const frames = states.map((s) => s.data as MetricsQueryResponse | undefined)
    const window = frames.find((f) => f?.window)?.window
    const error = states.find((s) => s.error)?.error
    const loading = states.some((s) => !s.received && !s.error)
    return {
      series: frames.flatMap((f, i) => (f ? toSeries(f, queries[i]) : [])),
      times: window ? timesOf(window) : [],
      loading,
      refreshing: !loading && states.some((s) => s.stale),
      ...(error && { error }),
    }
  }, [states, queries])
}
