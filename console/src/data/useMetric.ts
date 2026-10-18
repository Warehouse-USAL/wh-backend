import { useEffect, useState } from 'react'
import { ApiError, errorText } from '@/api/client'
import { type MetricQuery, type TimeWindow, queryMetric, stepMs } from '@/api/metrics'
import type { Series } from '@/widgets/scale'
import { type Range, useLive } from './live'

const STEP: Record<Range, string> = { '5m': '10s', '15m': '15s', '1h': '1m', '6h': '5m', '24h': '15m' }
const RANGE_MS: Record<Range, number> = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 }

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
 * The window ends at `now` rounded down to the step, and its buckets (`times`, epoch ms) are the
 * points VictoriaMetrics answers for: `from`, `from + step`, ... `to`, one per step of the range,
 * each covering the step that ends there. Aligned, every tick and every query of one range share
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
  /** Until the first answer (data or error) for this request and range. */
  loading: boolean
  /** A refetch is in flight while the previous answer stays on screen. */
  refreshing: boolean
  error?: ApiError
}

type Answer = { key?: string; fetchKey?: string; series: Series[]; times: number[]; error?: ApiError }

/**
 * Series for one query or several (their series concatenated in order). Refetches on every live
 * tick, on a range change and when the request changes by value; the previous series stay while
 * the next ones load, and a superseded request is aborted and its answer ignored.
 */
export function useMetric(req: MetricQuery | MetricQuery[]): MetricState {
  const { range, tick } = useLive()
  const json = JSON.stringify(Array.isArray(req) ? req : [req])
  const key = `${range}|${json}`
  const fetchKey = `${tick}|${key}`
  const [state, setState] = useState<Answer>({ series: [], times: [] })

  useEffect(() => {
    const controller = new AbortController()
    const { times, ...span } = windowFor(range, Date.now())
    const queries = JSON.parse(json) as MetricQuery[]
    Promise.all(queries.map((q) => queryMetric({ ...q, ...span }, controller.signal))).then(
      (results) => !controller.signal.aborted && setState({ key, fetchKey, series: results.flat(), times }),
      (error: unknown) =>
        !controller.signal.aborted && setState((s) => ({ ...s, key, fetchKey, error: toApiError(error) })),
    )
    return () => controller.abort()
  }, [fetchKey, key, range, json])

  const { series, times, error } = state
  const loading = state.key !== key
  return { series, times, loading, refreshing: !loading && state.fetchKey !== fetchKey, ...(error && { error }) }
}

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError(0, 'NETWORK_ERROR', errorText(error))
}
