import { PALETTE, type Point, type Series } from '@/widgets/scale'
import { api } from './client'

/** Aggregations `POST /metrics/query` accepts; each catalogue entry lists the ones its metric permits. */
export type Agg = 'avg' | 'min' | 'max' | 'sum' | 'last' | 'count' | 'rate' | 'increase' | 'p50' | 'p95' | 'p99'

/** What a widget asks for; the time window comes from the shell's range (see `windowFor`). */
export interface MetricQuery {
  metric: string
  agg: Agg
  filters?: Record<string, string>
  groupBy?: string[]
  /** Prefix for the series labels; an ungrouped series is named by it (or by the metric). */
  label?: string
}

/** ISO-8601 instants and a step such as `15s`, as the backend parses them. */
export interface TimeWindow {
  from: string
  to: string
  step: string
}

/** The backend's answer: each point is `[epochSeconds, value]`. */
export interface MetricsQueryResponse {
  metric: string
  unit: string
  step: string
  series: { labels: Record<string, string>; points: unknown[][] }[]
}

// Labels whose meaning has a colour on every page; anything else hashes into the extended palette.
const KNOWN: Record<string, string> = {
  '2xx': PALETTE.blue,
  '3xx': PALETTE.gray,
  '4xx': PALETTE.amber,
  '5xx': PALETTE.red,
  ok: PALETTE.blue,
  slow: PALETTE.amber,
  error: PALETTE.red,
  p50: PALETTE.blue,
  p95: PALETTE.amber,
  p99: PALETTE.red,
}

const EXTENDED = [PALETTE.blue, PALETTE.amber, PALETTE.red, PALETTE.gray, '#45a557', '#8e4ec6', '#12a594', '#d6409f']

/** A label always gets the same colour, so a series keeps it across ticks and pages. */
export function colorFor(label: string): string {
  if (Object.hasOwn(KNOWN, label)) return KNOWN[label]
  let hash = 0
  for (const ch of label) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return EXTENDED[hash % EXTENDED.length]
}

/** Runs one query; one series per group, labelled by its group values in `groupBy` order. */
export async function queryMetric(req: MetricQuery & TimeWindow, signal?: AbortSignal): Promise<Series[]> {
  const { metric, agg, filters = {}, groupBy = [], label, from, to, step } = req
  const body = JSON.stringify({ metric, from, to, step, filters, group_by: groupBy, agg })
  const response = (await api('/metrics/query', { method: 'POST', body, signal })) as MetricsQueryResponse
  return response.series.map((s) => {
    const name = [label, ...groupBy.map((k) => s.labels[k])].filter(Boolean).join(' ') || metric
    return { label: name, color: colorFor(name), points: s.points.flatMap(toPoint) }
  })
}

// The backend writes NaN and infinities as strings; those, nulls and short pairs are gaps, never 0.
function toPoint([t, v]: unknown[]): Point[] {
  return typeof t === 'number' && typeof v === 'number' && Number.isFinite(v) ? [{ t: t * 1000, v }] : []
}
