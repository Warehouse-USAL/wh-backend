import { PALETTE, type Point, type Series } from '@/widgets/scale'

/** Aggregations `POST /metrics/query` accepts; each catalogue entry lists the ones its metric permits. */
export type Agg = 'avg' | 'min' | 'max' | 'sum' | 'last' | 'count' | 'rate' | 'increase' | 'p50' | 'p95' | 'p99'

/** What a widget asks for; the time window comes from the shell's range (the stream's `window`). */
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
  /** Stream frames only: the window answered, its first and last bucket (ISO) and step. */
  window?: TimeWindow
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

const EXTENDED = [PALETTE.blue, PALETTE.amber, PALETTE.red, PALETTE.gray, 'var(--s-green)', 'var(--s-purple)', 'var(--s-teal)', 'var(--s-pink)']

/** A label always gets the same colour, so a series keeps it across frames and pages. */
export function colorFor(label: string): string {
  if (Object.hasOwn(KNOWN, label)) return KNOWN[label]
  let hash = 0
  for (const ch of label) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return EXTENDED[hash % EXTENDED.length]
}

const UNIT_MS = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }

/** `15s` -> 15000; the step grammar the backend accepts (`\\d+[smhd]`). */
export function stepMs(step: string): number {
  return Number(step.slice(0, -1)) * UNIT_MS[step.slice(-1) as keyof typeof UNIT_MS]
}

/** One series per group, labelled by the label prefix then its group values in `groupBy` order. */
export function toSeries(response: MetricsQueryResponse, { metric, groupBy = [], label }: MetricQuery): Series[] {
  return response.series.map((s) => {
    const name = [label, ...groupBy.map((k) => s.labels[k])].filter(Boolean).join(' ') || metric
    return { label: name, color: colorFor(name), points: s.points.flatMap(toPoint) }
  })
}

// The backend writes NaN and infinities as strings; those, nulls and short pairs are gaps, never 0.
function toPoint([t, v]: unknown[]): Point[] {
  return typeof t === 'number' && typeof v === 'number' && Number.isFinite(v) ? [{ t: t * 1000, v }] : []
}
