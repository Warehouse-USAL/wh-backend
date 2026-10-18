import { useEffect, useState } from 'react'
import { ApiError, errorText } from '@/api/client'
import { type MetricQuery, type TimeWindow, queryMetric } from '@/api/metrics'
import type { Series } from '@/widgets/scale'
import { type Range, useLive } from './live'

const STEP: Record<Range, string> = { '5m': '10s', '15m': '15s', '1h': '1m', '6h': '5m', '24h': '15m' }
const RANGE_MS: Record<Range, number> = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 }
const UNIT_MS = { s: 1000, m: 60_000, h: 3_600_000 }

/** About 30-100 buckets per range, well under the backend's 11000 points and above its 10 s minimum. */
export function stepFor(range: Range): string {
  return STEP[range]
}

/**
 * The window ends at `now` rounded down to the step. VictoriaMetrics places buckets at
 * `from + k * step`, so an aligned window yields the same bucket times on every tick and for every
 * query of one range; series stay comparable point by point. Series that miss a bucket keep the gap:
 * the widgets lay series onto the union of their timestamps (`plotData`) and the helpers in
 * `presets.ts` match points by timestamp, so no zero-filling happens anywhere.
 */
export function windowFor(range: Range, now: number): TimeWindow {
  const step = stepFor(range)
  const stepMs = Number(step.slice(0, -1)) * UNIT_MS[step.slice(-1) as keyof typeof UNIT_MS]
  const to = Math.floor(now / stepMs) * stepMs
  return { from: new Date(to - RANGE_MS[range]).toISOString(), to: new Date(to).toISOString(), step }
}

export interface MetricState {
  series: Series[]
  loading: boolean
  error?: ApiError
}

/**
 * Series for one query or several (their series concatenated in order). Refetches on every live
 * tick, on a range change and when the request changes by value; the previous series stay while
 * the next ones load, and a superseded request is aborted and its answer ignored.
 */
export function useMetric(req: MetricQuery | MetricQuery[]): MetricState {
  const { range, tick } = useLive()
  const key = JSON.stringify(Array.isArray(req) ? req : [req])
  const fetchKey = `${range}|${tick}|${key}`
  const [state, setState] = useState<{ key?: string; series: Series[]; error?: ApiError }>({ series: [] })

  useEffect(() => {
    const controller = new AbortController()
    const span = windowFor(range, Date.now())
    const queries = JSON.parse(key) as MetricQuery[]
    Promise.all(queries.map((q) => queryMetric({ ...q, ...span }, controller.signal))).then(
      (results) => !controller.signal.aborted && setState({ key: fetchKey, series: results.flat() }),
      (error: unknown) => !controller.signal.aborted && setState((s) => ({ key: fetchKey, series: s.series, error: toApiError(error) })),
    )
    return () => controller.abort()
  }, [fetchKey, key, range])

  const { series, error } = state
  return { series, loading: state.key !== fetchKey, ...(error && { error }) }
}

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError(0, 'NETWORK_ERROR', errorText(error))
}
