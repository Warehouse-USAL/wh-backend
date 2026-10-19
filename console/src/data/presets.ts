import type { MetricQuery } from '@/api/metrics'
import { PALETTE, type Point, type Series, type Tone, toneFor } from '@/widgets/scale'

/** Queries for `useMetric` plus the pure step that turns their series into what the widget shows. */
export interface Preset<T = Series[]> {
  queries: MetricQuery[]
  select(series: Series[]): T
}

type Filters = Record<string, string>
type As = { label: string; color: string }

const HTTP = 'http.server.duration'
const MONGO = 'mongo.command.duration'
const STATUS = ['2xx', '3xx', '4xx', '5xx']
const OUTCOME = ['ok', 'slow', 'error']
const QUANTILES = ['p50', 'p95', 'p99'] as const

export function rate(metric: string, filters: Filters = {}, groupBy: string[] = []): MetricQuery {
  return { metric, agg: 'rate', filters, groupBy }
}

/** The last point's value, if the series has any. */
export function latest(series: Series | undefined): number | undefined {
  return series?.points.at(-1)?.v
}

/** Per-timestamp sum over the union of the series' timestamps. */
export function sumSeries(series: Series[], as: As): Series {
  const sums = new Map<number, number>()
  for (const p of series.flatMap((s) => s.points)) sums.set(p.t, (sums.get(p.t) ?? 0) + p.v)
  return { ...as, points: toPoints(sums) }
}

/**
 * `a / b` at each timestamp where `b` is positive; elsewhere the point is missing, never 0 or NaN.
 * A missing `a` is missing too, unless `missingAs` says what it means (0 for "no errors counted").
 */
export function ratioSeries(a: Series | undefined, b: Series | undefined, { missingAs, ...as }: As & { missingAs?: number }): Series {
  const num = new Map(a?.points.map((p) => [p.t, p.v]))
  const out = new Map<number, number>()
  for (const { t, v } of b?.points ?? []) {
    const n = num.get(t) ?? missingAs
    if (v > 0 && n !== undefined) out.set(t, n / v)
  }
  return { ...as, points: toPoints(out) }
}

function toPoints(values: Map<number, number>): Point[] {
  return [...values].sort(([x], [y]) => x - y).map(([t, v]) => ({ t, v }))
}

const routeFilters = (route?: string, method?: string): Filters => ({ ...(route && { route }), ...(method && { method }) })

const asIs = (series: Series[]) => series

const ordered = (labels: string[]) => (series: Series[]) => labels.flatMap((l) => series.filter((s) => s.label === l))

const hitsQuery = (route?: string, method?: string): MetricQuery => ({
  metric: HTTP,
  agg: 'increase',
  filters: routeFilters(route, method),
  groupBy: ['status_class'],
})

/** Requests per bucket, stacked 2xx (bottom) to 5xx. */
export function httpHits(route?: string, method?: string): Preset {
  return { queries: [hitsQuery(route, method)], select: ordered(STATUS) }
}

export function httpLatency(route?: string, method?: string): Preset {
  const filters = routeFilters(route, method)
  return { queries: QUANTILES.map((agg) => ({ metric: HTTP, agg, filters, label: agg })), select: asIs }
}

// A bucket with traffic and no 5xx series counts 0 errors; a bucket without traffic has no rate.
const fivexxShare = (series: Series[]) =>
  ratioSeries(
    sumSeries(series.filter((s) => s.label === '5xx'), { label: '5xx', color: PALETTE.red }),
    sumSeries(series, { label: 'total', color: PALETTE.gray }),
    { label: '5xx %', color: PALETTE.red, missingAs: 0 },
  )

/** Share of requests answered 5xx, in percent, from the hits by status class. */
export function httpErrorRate(route?: string, method?: string): Preset {
  return {
    queries: [hitsQuery(route, method)],
    select: (series) => {
      const share = fivexxShare(series)
      return [{ ...share, points: share.points.map((p) => ({ t: p.t, v: p.v * 100 })) }]
    },
  }
}

/** 1 − the 5xx share over the whole range, for a tile; undefined when there was no traffic. */
export function successRate(route?: string, method?: string): Preset<{ value: string; tone: Tone } | undefined> {
  return {
    queries: [hitsQuery(route, method)],
    select: (series) => {
      const total = (labels?: string) => series.filter((s) => !labels || s.label === labels).flatMap((s) => s.points).reduce((n, p) => n + p.v, 0)
      const all = total()
      if (all <= 0) return undefined
      const pct = (1 - total('5xx') / all) * 100
      return { value: `${+pct.toFixed(2)}%`, tone: toneFor(pct) }
    },
  }
}

/** Mongo commands per bucket, stacked ok, slow, error. */
export function mongoOps(collection?: string): Preset {
  return {
    queries: [{ metric: MONGO, agg: 'increase', filters: collection ? { collection } : {}, groupBy: ['outcome'] }],
    select: ordered(OUTCOME),
  }
}

/** p95 per collection, or per command within one collection. */
export function mongoLatency(collection?: string): Preset {
  return {
    queries: [
      { metric: MONGO, agg: 'p95', filters: collection ? { collection } : {}, groupBy: [collection ? 'command' : 'collection'] },
    ],
    select: asIs,
  }
}
