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

const UNIT_MS = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }

/** `15s` -> 15000; the step grammar the backend accepts (`\\d+[smhd]`). */
export function stepMs(step: string): number {
  return Number(step.slice(0, -1)) * UNIT_MS[step.slice(-1) as keyof typeof UNIT_MS]
}

/** Runs one query; one series per group, labelled by its group values in `groupBy` order. */
export async function queryMetric(req: MetricQuery & TimeWindow, signal?: AbortSignal): Promise<Series[]> {
  const { metric, agg, filters = {}, groupBy = [], label, from, to, step } = req
  const body = JSON.stringify({ metric, from, to, step, filters, group_by: groupBy, agg })
  const response = await shared(body, stepMs(step), signal)
  return response.series.map((s) => {
    const name = [label, ...groupBy.map((k) => s.labels[k])].filter(Boolean).join(' ') || metric
    return { label: name, color: colorFor(name), points: s.points.flatMap(toPoint) }
  })
}

// Every widget of a page polls on the same tick, and several often ask the very same thing (a
// page's hits, error rate and success tile share one query). So identical bodies share one POST
// while it is in flight, and its answer is reused while `to` has not moved, for up to one step: a
// 6h or 24h window resends the same body for minutes. Errors are never kept.
type Entry = { promise: Promise<MetricsQueryResponse>; controller: AbortController; subscribers: number }
const inFlight = new Map<string, Entry>()
const answers = new Map<string, { response: MetricsQueryResponse; until: number }>()

const abortError = () => new DOMException('The request was aborted', 'AbortError')

function shared(body: string, ttl: number, signal?: AbortSignal): Promise<MetricsQueryResponse> {
  const now = Date.now()
  for (const [key, answer] of answers) if (answer.until <= now) answers.delete(key)
  const answer = answers.get(body)
  if (answer) return Promise.resolve(answer.response)
  if (signal?.aborted) return Promise.reject(abortError())

  let entry = inFlight.get(body)
  if (!entry || entry.controller.signal.aborted) {
    const controller = new AbortController()
    const post = () => api('/metrics/query', { method: 'POST', body, signal: controller.signal }) as Promise<MetricsQueryResponse>
    const created: Entry = {
      controller,
      subscribers: 0,
      promise: limited(post, controller.signal)
        .then((response) => {
          answers.set(body, { response, until: Date.now() + ttl })
          return response
        })
        .finally(() => inFlight.get(body) === created && inFlight.delete(body)),
    }
    entry = created
    inFlight.set(body, entry)
  }
  const joined = entry
  joined.subscribers++
  return new Promise((resolve, reject) => {
    const leave = () => {
      reject(abortError())
      // Deferred: a tick unsubscribes the old effect just before the new one asks for the same
      // body, and that hand-over must not cancel the request both of them want.
      if (--joined.subscribers === 0) queueMicrotask(() => joined.subscribers === 0 && joined.controller.abort())
    }
    signal?.addEventListener('abort', leave, { once: true })
    joined.promise.then(resolve, reject).finally(() => signal?.removeEventListener('abort', leave))
  })
}

// Browsers open about six connections per host; a burst beyond that would also queue every other
// call behind the metrics, and pile onto VictoriaMetrics. Queued requests start in order and are
// dropped, never sent, once nobody wants them any more.
const MAX_ACTIVE = 6
let active = 0
const queue: (() => void)[] = []

function limited<T>(run: () => Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const drop = () => {
      queue.splice(queue.indexOf(start), 1)
      reject(abortError())
    }
    const start = () => {
      signal.removeEventListener('abort', drop)
      active++
      run()
        .then(resolve, reject)
        .finally(() => {
          active--
          queue.shift()?.()
        })
    }
    if (active < MAX_ACTIVE) return start()
    queue.push(start)
    signal.addEventListener('abort', drop, { once: true })
  })
}

/** Forgets kept answers; for tests, which reuse bodies across cases. */
export function clearMetricAnswers(): void {
  answers.clear()
}

// The backend writes NaN and infinities as strings; those, nulls and short pairs are gaps, never 0.
function toPoint([t, v]: unknown[]): Point[] {
  return typeof t === 'number' && typeof v === 'number' && Number.isFinite(v) ? [{ t: t * 1000, v }] : []
}
