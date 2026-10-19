import type { Agg, MetricQuery } from '@/api/metrics'
import { PALETTE, type Series, type Tone, formatClock, formatCount, formatMs, formatTime, toneFor } from '@/widgets/scale'
import { RESTOCK_SCHEDULE, RESTOCK_SCHEDULE_OFFSET_MIN, nextDailyRun } from './infra/cron'
import { formatAge, formatBytes, percent } from './infra/format'
import { badShare, countTone, goodTile, lastValue, mapPoints, newest, peak, pick, scale, total } from './infra/series'
import type { ChartSpec, Ctx, GroupSpec, PageSpec, StatusQuery, StreamRow, TileSpec, TileValue, WidgetSpec } from './infra/spec'
import { RANGE_MS } from './useMetric'

/*
 * The Infrastructure pages as data, rendered by `PresetPage`. Every metric name, tag and
 * aggregation here is in the backend catalogue (`GET /metrics/catalog`; the test checks the
 * fixture). Panels of the boards that have no series are left out and said so in the page note,
 * never drawn as a zero.
 */

export type InfraPage = 'mongo' | 'backend' | 'bridge' | 'kafka' | 'minio' | 'cron' | 'edge' | 'telemetry'

const { blue, amber, red, gray } = PALETTE

function m(metric: string, agg: Agg, filters: Record<string, string> = {}, groupBy: string[] = [], label?: string): MetricQuery {
  return { metric, agg, filters, groupBy, ...(label && { label }) }
}

const quantiles = (metric: string, filters: Record<string, string> = {}) =>
  (['p50', 'p95', 'p99'] as const).map((agg) => m(metric, agg, filters, [], agg))

const minutes = (ctx: Ctx) => RANGE_MS[ctx.range] / 60_000
const lastRange = (what: string) => (ctx: Ctx) => `${what}, last ${ctx.range}`

const status = (title: string, service: string, show: StatusQuery['show'] = 'state', span: 2 | 3 = 3): WidgetSpec => ({
  kind: 'status',
  title,
  span,
  query: { service, show },
})

/** A tile of one number; `label` may name the range. */
function numberTile(title: string, query: MetricQuery[], read: (series: Series[]) => number | undefined, opts: { format?: (v: number) => string; tone?: (v: number) => Tone; label?: (ctx: Ctx) => string } = {}): TileSpec {
  const { format = formatCount, tone = () => 'ok', label } = opts
  return {
    kind: 'tile',
    title,
    span: 3,
    query,
    value: (series, ctx) => {
      const v = read(series)
      return v === undefined ? undefined : { value: format(v), tone: tone(v), ...(label && { label: label(ctx) }), ...(series.length === 1 && { spark: series[0].points.map((p) => p.v) }) }
    },
  }
}

/** Sum over the range, but only when some series answered (no series is no data, not 0). */
const sumIfAny = (series: Series[]) => (series.some((s) => s.points.length) ? total(series) : undefined)

const errorRate = (query: MetricQuery[], bad: string[], counted?: string[]): ChartSpec => ({
  kind: 'lines',
  title: 'Error rate',
  span: 2,
  area: true,
  query,
  select: badShare(bad, counted),
  format: percent,
})

// ---------------------------------------------------------------- MongoDB

const MONGO = 'mongo.command.duration'

/** The collections the backend's repositories use, in the boards' order. */
export const MONGO_COLLECTIONS = ['orders', 'products', 'positions', 'receptions', 'restock_orders', 'zones', 'lines', 'vehicles', 'users']

const ranked = (value: (s: Series) => number) => (series: Series[]) =>
  series
    .map((s) => ({ name: s.label, value: value(s) }))
    .filter((r) => Number.isFinite(r.value) && r.value > 0)
    .sort((a, b) => b.value - a.value)
    .slice(0, 5)

function collectionGroup(collection: string): GroupSpec {
  const ops = [m(MONGO, 'increase', { collection }, ['outcome'])]
  return {
    title: collection,
    mono: true,
    tag: 'collection',
    widgets: [
      { kind: 'bars', title: 'Requests', sub: 'by outcome, slow = 100 ms or more', span: 4, query: ops, select: pick(['ok', 'ok', blue], ['slow', 'slow', amber], ['error', 'failed', red]) },
      { kind: 'lines', title: 'Latency', sub: 'p50 / p95 / p99', span: 4, query: quantiles(MONGO, { collection }), format: formatMs },
      errorRate(ops, ['error']),
      { kind: 'tile', title: 'Ops OK', span: 2, query: ops, value: goodTile(['error']) },
    ],
  }
}

const mongo: PageSpec = {
  crumb: 'Infrastructure',
  title: 'MongoDB',
  tabs: ['Collections'],
  chips: ['database smartwarehouse'],
  groups: [
    {
      title: 'Overview',
      tag: 'all 9 MongoDB collections',
      tab: 'Collections',
      widgets: [
        { kind: 'toplist', title: 'Requests', sub: 'per min', span: 4, query: [m(MONGO, 'increase', {}, ['collection'])], color: blue, format: formatCount, rows: (s, ctx) => ranked((x) => total([x]) / minutes(ctx))(s) },
        { kind: 'toplist', title: 'Latency', sub: 'p95, slowest first', span: 4, query: [m(MONGO, 'p95', {}, ['collection'])], color: amber, format: formatMs, rows: ranked(peak) },
        { kind: 'toplist', title: 'Failures', sub: (ctx) => `last ${ctx.range}`, span: 4, query: [m(MONGO, 'increase', { outcome: 'error' }, ['collection'])], color: red, format: formatCount, rows: ranked((x) => total([x])) },
      ],
    },
    ...MONGO_COLLECTIONS.map(collectionGroup),
  ],
  note: 'Commands are timed by the backend’s Mongo command listener; slow means it succeeded in 100 ms or more (telemetry.mongo-slow-ms) and counts as OK.',
}

// ---------------------------------------------------------------- Backend

const ofMax = (label: string, max?: number) => (max === undefined ? label : `of ${formatCount(max)}`)

const backend: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Backend',
  tabs: ['Health', 'JVM', 'HTTP server', 'Mongo pool'],
  chips: [],
  groups: [
    {
      title: 'health',
      mono: true,
      tag: 'actuator /health and process, from /console/status',
      tab: 'Health',
      widgets: [status('Backend', 'backend'), status('Uptime', 'backend', 'uptime'), status('Mongo health', 'mongodb'), status('Kafka health', 'redpanda')],
    },
    {
      title: 'jvm',
      mono: true,
      tag: 'Java 21',
      tab: 'JVM',
      widgets: [
        { kind: 'bars', title: 'Heap', sub: 'used, MB', span: 4, query: [m('jvm.heap.used', 'max', {}, [], 'heap used, MB')], select: (s) => scale(1e-6)(pick(['heap used, MB', 'heap used, MB', blue])(s)) },
        { kind: 'lines', title: 'GC time', sub: 'ms spent per bucket, by collector', span: 4, query: [m('jvm.gc.time', 'increase', {}, ['gc'])], format: formatMs },
        { kind: 'bars', title: 'Threads', sub: 'live', span: 4, query: [m('jvm.threads', 'max', {}, [], 'live threads')], select: pick(['live threads', 'live threads', gray]) },
      ],
    },
    {
      title: 'http server',
      mono: true,
      tag: 'Tomcat',
      tab: 'HTTP server',
      widgets: [
        {
          kind: 'bars',
          title: 'Busy threads',
          sub: (_, s) => ofMax('busy', lastValue(s, 'max')),
          span: 4,
          query: [m('tomcat.threads', 'max', {}, ['state'])],
          select: pick(['busy', 'busy threads', blue]),
        },
        { kind: 'bars', title: 'Active requests', sub: 'in flight', span: 4, query: [m('http.server.active', 'max', {}, [], 'active requests')], select: pick(['active requests', 'active requests', blue]) },
        {
          kind: 'tile',
          title: 'Connection saturation',
          sub: 'current of max; Tomcat exposes no rejected-connections count',
          span: 4,
          query: [m('tomcat.connections', 'max', {}, ['state'])],
          value: (series) => {
            const current = series.find((s) => s.label === 'current')
            const max = lastValue(series, 'max')
            const now = current?.points.at(-1)?.v
            if (now === undefined || !max) return undefined
            const pct = (now / max) * 100
            return {
              value: percent(pct),
              tone: pct < 80 ? 'ok' : pct < 100 ? 'warn' : 'error',
              label: `Connections, ${formatCount(now)} of ${formatCount(max)}. Tomcat exposes no rejected-connections count`,
              spark: current?.points.map((p) => p.v),
            }
          },
        },
      ],
    },
    {
      title: 'mongo connection pool',
      mono: true,
      tag: 'driver pool',
      tab: 'Mongo pool',
      widgets: [
        {
          kind: 'bars',
          title: 'Connections',
          sub: (_, s) => {
            const [used, free] = [lastValue(s, 'in_use'), lastValue(s, 'available')]
            return used === undefined || free === undefined ? 'in use' : `in use of ${used + free} open`
          },
          span: 4,
          query: [m('mongo.pool.connections', 'max', {}, ['state'])],
          select: pick(['in_use', 'in use', blue]),
        },
        { kind: 'lines', title: 'Checkout wait', sub: 'p50 / p95 / p99', span: 4, query: quantiles('mongo.pool.checkout'), format: formatMs },
        {
          kind: 'bars',
          title: 'Checkouts failed',
          sub: 'by reason, timeout is a pool wait timeout',
          span: 4,
          query: [m('mongo.pool.checkout.failed', 'increase', {}, ['reason'])],
          select: pick(['timeout', 'timeout', amber], ['pool_closed', 'pool closed', gray], ['connection_error', 'connection error', red], ['unknown', 'unknown', gray]),
        },
      ],
    },
  ],
  note: 'Not shown, no series exists: GC pause quantiles (the JVM reports total GC time, shown instead) and Tomcat rejected connections (Tomcat has no such counter; saturation stands in).',
}

// ---------------------------------------------------------------- Bridge

const BM = 'bridge.messages'
const LAG = 'bridge.consumer.lag'
const CALLBACK = 'bridge.callback.duration'
/** Every message ends ok, dropped or error; `retry` counts attempts, not messages. */
const SETTLED = ['ok', 'dropped', 'error']
const FAILED = ['dropped', 'error']

const lagTile = (title: string): TileSpec =>
  numberTile(title, [m(LAG, 'last', { flow: 'orders_out' })], (s) => lastValue(s), { tone: (v) => (v < 100 ? 'ok' : 'warn') })

const resultBars = (flow: string, okAs: string, sub: string, span: 4 | 8): ChartSpec => ({
  kind: 'bars',
  title: 'Messages',
  sub,
  span,
  query: [m(BM, 'increase', { flow }, ['result'])],
  select: pick(['ok', okAs, blue], ['retry', 'retry', amber], ['dropped', 'dropped', gray], ['error', 'failed', red]),
})

const processing = (flow: string, sub: string): ChartSpec => ({
  kind: 'lines',
  title: 'Processing time',
  sub: `p50 / p95 / p99, ${sub}`,
  span: 12,
  query: quantiles('bridge.processing.duration', { flow }),
  format: formatMs,
})

function callbackGroup(flow: string, path: string, what: string): GroupSpec {
  const messages = [m(BM, 'increase', { flow }, ['result'])]
  return {
    title: `POST ${path}`,
    mono: true,
    tag: `Infra to backend: ${what} from the queue, flow ${flow}`,
    right: 'X-Api-Key',
    widgets: [
      resultBars(flow, 'delivered', 'by result', 4),
      { kind: 'lines', title: 'Callback latency', sub: 'p50 / p95 / p99, per attempt', span: 4, query: quantiles(CALLBACK, { flow }), format: formatMs },
      errorRate(messages, FAILED, SETTLED),
      { kind: 'tile', title: 'Delivered', span: 2, query: messages, value: goodTile(FAILED, SETTLED) },
      processing(flow, 'AMQP delivery to ack or requeue, including retry waits'),
    ],
  }
}

const LEVEL_TONE: Record<string, StreamRow['tone']> = { error: 'error', warn: 'warn', info: 'info' }

const bridge: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Bridge',
  tabs: ['Overview', 'Backend to Infra', 'Infra to backend', 'Exchanges'],
  chips: [],
  groups: [
    {
      title: 'Link health',
      tag: 'the bridge itself',
      tab: 'Overview',
      widgets: [
        lagTile('Kafka consumer lag, group wh-bridge'),
        { kind: 'tile', title: 'RabbitMQ publisher confirms', span: 3, query: [m(BM, 'increase', { flow: 'orders_out' }, ['result'])], value: goodTile(FAILED, SETTLED) },
        numberTile('Retries', [m(BM, 'increase', { result: 'retry' })], sumIfAny, { tone: countTone, label: lastRange('Retries, all flows') }),
        status('GET /healthz', 'bridge'),
      ],
    },
    {
      title: 'Backend to Infra',
      tag: 'Kafka orders, republished to RabbitMQ amq.topic, flow orders_out',
      tab: 'Backend to Infra',
      widgets: [
        resultBars('orders_out', 'confirmed', 'by publish result', 4),
        { kind: 'lines', title: 'Publish latency', sub: 'p50 / p95 / p99, per attempt including its confirm', span: 4, query: quantiles('bridge.publish.duration', { flow: 'orders_out' }), format: formatMs },
        errorRate([m(BM, 'increase', { flow: 'orders_out' }, ['result'])], FAILED, SETTLED),
        { kind: 'tile', title: 'Confirmed', span: 2, query: [m(BM, 'increase', { flow: 'orders_out' }, ['result'])], value: goodTile(FAILED, SETTLED) },
        processing('orders_out', 'Kafka record to commit, including retry waits'),
      ],
    },
    { ...callbackGroup('orders_in', '/infra/orders/{orderId}/status', 'order status'), tab: 'Infra to backend' },
    callbackGroup('vehicles_in', '/infra/vehicles/{vehicleId}/telemetry', 'vehicle telemetry'),
    {
      title: 'Exchanges',
      tag: 'the bridge’s log lines, newest first',
      tab: 'Exchanges',
      widgets: [
        {
          kind: 'stream',
          source: 'logs',
          title: 'Exchanges',
          sub: 'consumed, published, delivered, retried and dropped messages',
          span: 12,
          query: { service: 'bridge', limit: 50 },
          rows: (lines) =>
            lines.map((l) => ({
              time: formatClock(l.time),
              tag: l.level ?? '-',
              tone: LEVEL_TONE[l.level ?? ''],
              text: l.requestId ? `${l.message ?? ''} req=${l.requestId}` : (l.message ?? ''),
            })),
        },
      ],
    },
  ],
  note: 'Retry policy: 2xx acked, 4xx dropped, 408, 429, 5xx and network errors retried with backoff from 1 s to 30 s. Not shown, no series exists: retries in flight (retries are counted as they happen).',
}

// ---------------------------------------------------------------- Kafka

const KM = 'kafka.messages'

function topicGroup(topic: string, desc: string): GroupSpec {
  const published = [m(KM, 'increase', { topic, direction: 'out' }, ['result'])]
  return {
    title: topic,
    mono: true,
    tag: desc,
    widgets: [
      { kind: 'bars', title: 'Messages', sub: 'by publish result', span: 4, query: published, select: pick(['ok', 'published', blue], ['error', 'failed', red]) },
      { kind: 'lines', title: 'Publish latency', sub: 'p50 / p95 / p99, send to broker acknowledgement', span: 4, query: quantiles('kafka.publish.duration', { topic }), format: formatMs },
      errorRate(published, ['error']),
      { kind: 'tile', title: 'Published OK', span: 2, query: published, value: goodTile(['error']) },
    ],
  }
}

const kafka: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Kafka',
  tabs: ['Topics', 'Consumer groups'],
  chips: ['broker redpanda'],
  groups: [
    {
      title: 'Broker',
      tag: 'Redpanda',
      tab: 'Topics',
      widgets: [
        status('Broker', 'redpanda'),
        numberTile('Topics', [m(KM, 'increase', { direction: 'out' }, ['topic'])], (s) => (s.some((x) => total([x]) > 0) ? s.filter((x) => total([x]) > 0).length : undefined), {
          label: lastRange('Topics published to'),
        }),
        lagTile('Consumer lag, group wh-bridge'),
        {
          kind: 'tile',
          title: 'Publish failures',
          span: 3,
          query: [m(KM, 'increase', { direction: 'out' }, ['result'])],
          value: (series, ctx) => {
            const all = total(series)
            if (all <= 0) return undefined
            const pct = (total(series.filter((s) => s.label === 'error')) / all) * 100
            return { value: percent(pct), tone: toneFor(100 - pct), label: lastRange('Publish failures')(ctx) }
          },
        },
      ],
    },
    topicGroup('orders', 'published on order create and change, consumed by the bridge'),
    topicGroup('order.completed', 'published when an order completes'),
    {
      title: 'wh-bridge',
      mono: true,
      tag: 'consumer group on orders',
      tab: 'Consumer groups',
      widgets: [
        { kind: 'bars', title: 'Consumer lag', sub: 'messages behind', span: 4, query: [m(LAG, 'max', { flow: 'orders_out' }, [], 'lag')], select: pick(['lag', 'lag', gray]) },
        {
          kind: 'bars',
          title: 'Consumed',
          sub: 'messages per min',
          span: 4,
          query: [m(BM, 'rate', { flow: 'orders_out' }, ['result'])],
          select: (s) => scale(60)(pick(['consumed', 'consumed', blue])([sumAll(s.filter((x) => SETTLED.includes(x.label)), 'consumed')])),
        },
        { ...processing('orders_out', 'consume to RabbitMQ confirm and commit'), span: 4 },
      ],
    },
  ],
  note: 'Publishing is asynchronous and a failure is only logged, there is no outbox; a send that throws before reaching the broker is counted in the messages but has no latency.',
}

function sumAll(series: Series[], label: string): Series {
  const sums = new Map<number, number>()
  for (const p of series.flatMap((s) => s.points)) sums.set(p.t, (sums.get(p.t) ?? 0) + p.v)
  return { label, color: blue, points: [...sums].sort(([a], [b]) => a - b).map(([t, v]) => ({ t, v })) }
}

// ---------------------------------------------------------------- MinIO

const minio: PageSpec = {
  crumb: 'Infrastructure',
  title: 'MinIO',
  tabs: ['Storage', 'Requests'],
  chips: [],
  groups: [
    {
      title: 'minio',
      mono: true,
      tag: 'object storage for files',
      tab: 'Storage',
      widgets: [
        status('MinIO', 'minio'),
        numberTile('Bucket size', [m('minio.bucket.size', 'last')], (s) => lastValue(s), { format: formatBytes, label: () => 'Bucket size, cluster total (one bucket)' }),
        numberTile('Objects', [m('minio.objects', 'last')], (s) => lastValue(s)),
        numberTile('Errors', [m('minio.errors', 'increase')], sumIfAny, { tone: countTone, label: lastRange('Errors, 4xx and 5xx') }),
        {
          kind: 'bars',
          title: 'Requests',
          sub: 'by result',
          span: 4,
          query: [m('minio.requests', 'increase', {}, [], 'requests'), m('minio.errors', 'increase', {}, ['class'], 'errors')],
          select: (s) => {
            const errors = new Map<number, number>()
            for (const p of s.filter((x) => x.label !== 'requests').flatMap((x) => x.points)) errors.set(p.t, (errors.get(p.t) ?? 0) + p.v)
            const requests = s.find((x) => x.label === 'requests')
            const ok = requests && { label: 'ok', color: blue, points: requests.points.map((p) => ({ t: p.t, v: Math.max(0, p.v - (errors.get(p.t) ?? 0)) })) }
            return [...(ok ? [ok] : []), ...pick(['errors 4xx', '4xx', amber], ['errors 5xx', '5xx', red])(s)]
          },
        },
        { kind: 'lines', title: 'Latency', sub: 'time to first byte, p50 / p95 / p99', span: 4, query: quantiles('minio.latency'), select: scale(1000), format: formatMs },
        {
          kind: 'bars',
          title: 'Traffic',
          sub: 'KB per min',
          span: 4,
          query: [m('minio.traffic.received', 'rate', {}, [], 'received'), m('minio.traffic.sent', 'rate', {}, [], 'sent')],
          select: (s) => scale(60 / 1000)(pick(['received', 'received', blue], ['sent', 'sent', gray])(s)),
        },
      ],
    },
  ],
  note: 'MinIO’s cluster metrics, scraped by the collector every 30 s. Bucket size and objects are cluster totals: per-bucket usage is not scraped, and the backend uses one bucket.',
}

// ---------------------------------------------------------------- Restock cron

const RUN = (field: string, label?: string) => m('job.run', 'last', { job: 'restock', field }, ['result'], label)
const DURATION = RUN('duration_ms')
const RESULT: Record<string, [string, Tone]> = { ok: ['OK', 'ok'], error: ['Failed', 'error'] }
const DAY = 86_400_000
/**
 * Last run, last duration and the list read 2 days at a 1 m step, so each run lands in its own
 * bucket; a bucket covers the minute before its time, so the run started in the minute one step
 * earlier, shown to the minute. The history reads 30 days at a 1 d step, a bar per day.
 */
const RECENT = '2d'
const HISTORY = '30d'
const runTime = (bucket: number) => bucket - 60_000

const startOfDay = (t: number) => new Date(t).setHours(0, 0, 0, 0)
function dayWord(t: number, now: number): string {
  const days = Math.round((startOfDay(t) - startOfDay(now)) / DAY)
  return days === 0 ? 'today' : days === 1 ? 'tomorrow' : days === -1 ? 'yesterday' : new Date(t).toLocaleDateString()
}

/** The next run of a daily schedule; unknown for any other form (the console cannot evaluate it). */
export function nextRunValue(schedule: string, offsetMin: number): TileSpec['value'] {
  return (_, { now }) => {
    const next = nextDailyRun(schedule, now, offsetMin)
    const label = `Next run, schedule ${schedule} in UTC (RESTOCK_SCHEDULE default)`
    return next === undefined ? { value: 'Unknown', tone: 'warn', label: `${label}, not a daily time` } : { value: `${formatTime(next, false)} ${dayWord(next, now)}`, tone: 'ok', label }
  }
}

function lastRun(read: (run: NonNullable<ReturnType<typeof newest>>) => TileValue): TileSpec['value'] {
  return (series) => {
    const run = newest(series)
    return run && read(run)
  }
}

const runRows = (series: Series[], ctx: Ctx): StreamRow[] => {
  const suggestions = new Map(series.filter((s) => s.label.startsWith('suggestions')).flatMap((s) => s.points.map((p) => [p.t, p.v])))
  return series
    .filter((s) => s.label.startsWith('duration '))
    .flatMap((s) => s.points.map((p) => ({ ...p, result: s.label.slice('duration '.length) })))
    .sort((a, b) => b.t - a.t)
    .map(({ t, v, result }) => {
      const n = suggestions.get(t)
      const day = dayWord(runTime(t), ctx.now)
      return {
        time: `${day === 'today' ? '' : `${day} `}${formatTime(runTime(t), false)}`,
        tag: result === 'ok' ? 'ok' : 'failed',
        tone: result === 'ok' ? 'ok' : 'error',
        text: n === undefined ? formatMs(v) : `${n} suggestions, ${formatMs(v)}`,
      }
    })
}

const cron: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Restock cron',
  tabs: ['Runs'],
  chips: [],
  groups: [
    {
      title: 'restock-cron',
      mono: true,
      tag: 'scheduled run that asks the backend for restock suggestions',
      tab: 'Runs',
      widgets: [
        {
          kind: 'tile',
          title: 'Last run',
          span: 3,
          range: RECENT,
          query: [DURATION],
          value: (series, ctx) => lastRun(({ t, label }) => ({ value: RESULT[label]?.[0] ?? label, tone: RESULT[label]?.[1] ?? 'warn', label: `Last run, ${dayWord(runTime(t), ctx.now)} ${formatTime(runTime(t), false)}` }))(series, ctx),
        },
        { kind: 'tile', title: 'Last duration', span: 3, range: RECENT, query: [DURATION], value: lastRun(({ v }) => ({ value: formatMs(v), tone: 'ok' })) },
        { kind: 'tile', title: 'Next run', span: 3, query: [], value: nextRunValue(RESTOCK_SCHEDULE, RESTOCK_SCHEDULE_OFFSET_MIN) },
        {
          kind: 'tile',
          title: 'Runs ok',
          span: 3,
          range: HISTORY,
          query: [DURATION],
          value: (series, ctx) => {
            const runs = series.flatMap((s) => s.points.map(() => s.label))
            if (!runs.length) return undefined
            const pct = (runs.filter((r) => r === 'ok').length / runs.length) * 100
            return { value: percent(pct), tone: toneFor(pct), label: lastRange('Runs ok')(ctx) }
          },
        },
        {
          kind: 'bars',
          title: 'Runs',
          sub: 'by result, per day, last 30 days',
          span: 4,
          range: HISTORY,
          query: [DURATION],
          select: (s) => mapPoints(pick(['ok', 'ok', blue], ['error', 'failed', red])(s), () => 1),
        },
        { kind: 'lines', title: 'Duration', sub: 'per day’s last run, last 30 days', span: 4, range: HISTORY, query: [DURATION], select: pick(['ok', 'ok', blue], ['error', 'failed', red]), format: formatMs },
        {
          kind: 'bars',
          title: 'Suggestions',
          sub: 'rows applied, per day’s last run',
          span: 4,
          range: HISTORY,
          query: [m('job.run', 'last', { job: 'restock', field: 'suggestions' }, [], 'suggestions')],
          select: pick(['suggestions', 'suggestions', gray]),
        },
      ],
    },
    {
      title: 'recent runs',
      mono: true,
      tag: 'newest first, last 2 days',
      widgets: [{ kind: 'stream', source: 'metrics', title: 'Runs', sub: 'from the job.run samples the cron pushes after each run', span: 12, range: RECENT, query: [RUN('duration_ms', 'duration'), RUN('suggestions', 'suggestions')], rows: runRows }],
    },
  ],
  note: `The schedule is not readable by the console: it shows the default RESTOCK_SCHEDULE "${RESTOCK_SCHEDULE}" from docker-compose.yml, evaluated in UTC (the cron image has no tzdata). Runs are what the cron pushed: the history over the last 30 days, a day per bar (a day with more than one run shows its last), the last run and the list over the last 2 days, to the minute. They do not follow the range picker. A failed run reports 0 suggestions.`,
}

// ---------------------------------------------------------------- Edge

const ED = 'edge.duration'
const BY_CODE = [m(ED, 'increase', {}, ['code'])]

const byClass = (series: Series[]): Series[] => {
  const classes = ['2xx', '3xx', '4xx', '5xx']
  return classes.flatMap((c) => {
    const members = series.filter((s) => s.label[0] === c[0])
    return members.length ? [sumAll(members, c)].map((s) => ({ ...s, color: c === '2xx' ? blue : c === '3xx' ? gray : c === '4xx' ? amber : red })) : []
  })
}

const edge: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Edge (prod)',
  tabs: ['Requests', 'Auth failures'],
  chips: ['production only', 'proxy Caddy'],
  groups: [
    {
      title: 'caddy',
      mono: true,
      tag: 'prod proxy in front of the backend',
      tab: 'Requests',
      widgets: [
        {
          kind: 'tile',
          title: 'Caddy',
          span: 3,
          query: [m('edge.up', 'last')],
          value: (series) => {
            const up = lastValue(series)
            return up === undefined ? undefined : up >= 1 ? { value: 'UP', tone: 'ok', label: 'Caddy, last metrics scrape succeeded' } : { value: 'DOWN', tone: 'error', label: 'Caddy, last metrics scrape failed' }
          },
        },
        {
          kind: 'tile',
          title: 'Upstream errors',
          span: 3,
          query: [m('edge.errors', 'increase', {}, [], 'errors'), m('edge.requests', 'increase', {}, [], 'requests')],
          value: (series) => {
            const all = total(series.filter((s) => s.label === 'requests'))
            if (all <= 0) return undefined
            const pct = (total(series.filter((s) => s.label === 'errors')) / all) * 100
            return { value: percent(pct), tone: toneFor(100 - pct), label: 'Upstream errors, handler errors per request' }
          },
        },
        {
          kind: 'tile',
          title: '401 and 403',
          span: 3,
          query: BY_CODE,
          value: (series) => {
            const all = total(series)
            if (all <= 0) return undefined
            const pct = (total(series.filter((s) => s.label === '401' || s.label === '403')) / all) * 100
            return { value: percent(pct), tone: toneFor(100 - pct) }
          },
        },
        numberTile('Requests per min', [m('edge.requests', 'rate')], (s) => lastValue(s), { format: (v) => formatCount(v * 60) }),
        { kind: 'bars', title: 'Hits', sub: 'by status class', span: 4, query: BY_CODE, select: byClass },
        { kind: 'lines', title: 'Latency', sub: 'p50 / p95 / p99', span: 4, query: quantiles(ED), select: scale(1000), format: formatMs },
        {
          kind: 'bars',
          title: 'Auth failures',
          sub: 'per min',
          span: 2,
          query: [m(ED, 'rate', { code: '401' }, [], '401'), m(ED, 'rate', { code: '403' }, [], '403')],
          select: (s) => scale(60)(pick(['401', '401', amber], ['403', '403', red])(s)),
        },
        {
          kind: 'lines',
          title: 'Error rate',
          sub: '5xx',
          span: 2,
          area: true,
          query: BY_CODE,
          select: (s) => badShare(s.filter((x) => x.label.startsWith('5')).map((x) => x.label))(s),
          format: percent,
        },
      ],
    },
  ],
  empty: 'Only exists in production, once Caddy’s metrics are scraped (docs/CONSOLE.md, Caddy step)',
  note: 'Caddy’s health is whether the collector’s last scrape of its metrics succeeded, not a probe of the proxy itself. Upstream errors are Caddy’s handler errors: it counts no failures per upstream.',
}

// ---------------------------------------------------------------- Telemetry

/** Older than this, a source counts as stopped: pushed every 10 s, scraped every 30 s. */
const PUSHED_MS = 60_000
const SCRAPED_MS = 120_000

/**
 * One gauge per source that reports on its own (counters and histograms have no `last`). Vehicle
 * battery only exists while a vehicle reports, so its absence says nothing about the pipeline.
 */
const FRESHNESS: { source: string; metric: string; staleMs: number; optional?: boolean }[] = [
  { source: 'backend', metric: 'jvm.heap.used', staleMs: PUSHED_MS },
  { source: 'backend', metric: 'http.server.active', staleMs: PUSHED_MS },
  { source: 'backend', metric: 'mongo.pool.connections', staleMs: PUSHED_MS },
  { source: 'vehicles', metric: 'wh.vehicle.battery', staleMs: PUSHED_MS, optional: true },
  { source: 'bridge', metric: 'bridge.consumer.lag', staleMs: PUSHED_MS },
  { source: 'minio', metric: 'minio.objects', staleMs: SCRAPED_MS },
  { source: 'otel-collector', metric: 'collector.queue.size', staleMs: SCRAPED_MS },
  { source: 'victoriametrics', metric: 'vm.series.active', staleMs: SCRAPED_MS },
]

const freshnessRows = (series: Series[], ctx: Ctx): StreamRow[] =>
  FRESHNESS.map(({ source, metric, staleMs, optional }) => {
    const t = newest(series.filter((s) => s.label === metric))?.t
    // No sample in the window: the source is down or never reported (no vehicle reporting is normal).
    if (t === undefined) return { time: source, tag: 'none', ...(!optional && { tone: 'error' as const }), text: metric }
    const age = ctx.now - t
    return { time: source, tag: formatAge(age), tone: age > staleMs ? 'warn' : 'ok', text: metric }
  })

const VM_RANGE = '/api/v1/query_range'
const vmQuantile = (q: string, label: string) => m('vm.query.duration', 'max', { path: VM_RANGE, quantile: q }, [], label)

const telemetry: PageSpec = {
  crumb: 'Infrastructure',
  title: 'Telemetry',
  tabs: ['Collector', 'VictoriaMetrics', 'Freshness'],
  chips: ['pipeline OTLP to VictoriaMetrics'],
  groups: [
    {
      title: 'otel-collector',
      mono: true,
      tag: 'OTLP in, VictoriaMetrics out',
      tab: 'Collector',
      widgets: [
        status('Collector', 'otel-collector'),
        numberTile('Metric points refused', [m('collector.refused', 'increase')], sumIfAny, { tone: countTone, label: lastRange('Metric points refused') }),
        numberTile('Metric points dropped', [m('collector.dropped', 'increase')], sumIfAny, { tone: countTone, label: lastRange('Metric points dropped, export queue full') }),
        numberTile('Export queue size', [m('collector.queue.size', 'last')], (s) => lastValue(s)),
        {
          kind: 'bars',
          title: 'Metric points',
          sub: 'received per min',
          span: 4,
          query: [m('collector.received', 'rate', {}, [], 'received'), m('collector.refused', 'rate', {}, [], 'refused')],
          select: (s) => scale(60)(pick(['received', 'received', blue], ['refused', 'refused', red])(s)),
        },
        {
          kind: 'bars',
          title: 'Exports to VictoriaMetrics',
          sub: 'metric points by result',
          span: 4,
          query: [m('collector.exported', 'increase', {}, [], 'exported'), m('collector.export.failed', 'increase', {}, [], 'failed')],
          select: pick(['exported', 'exported', blue], ['failed', 'failed', red]),
        },
        { kind: 'lines', title: 'Export latency', sub: 'p50 / p95 / p99', span: 4, query: quantiles('collector.export.duration', { net_peer_name: 'victoriametrics' }), format: formatMs },
      ],
    },
    {
      title: 'victoriametrics',
      mono: true,
      tag: 'storage and query, retention 800 days',
      tab: 'VictoriaMetrics',
      widgets: [
        status('VictoriaMetrics', 'victoriametrics'),
        numberTile('Active series', [m('vm.series.active', 'last', { type: 'storage/hour_metric_ids' })], (s) => lastValue(s)),
        {
          kind: 'tile',
          title: 'Disk used',
          span: 3,
          query: [m('vm.disk.used', 'count', {}, [], 'used'), m('vm.disk.free', 'last', {}, [], 'free')],
          value: (series) => {
            const [used, free] = [lastValue(series, 'used'), lastValue(series, 'free')]
            if (used === undefined || free === undefined || used + free <= 0) return undefined
            const pct = (used / (used + free)) * 100
            return { value: percent(+pct.toFixed(0)), tone: pct < 80 ? 'ok' : pct < 90 ? 'warn' : 'error', label: `Disk used, ${formatBytes(used)} of data` }
          },
        },
        numberTile('Rows rejected', [m('vm.rows.rejected', 'increase')], sumIfAny, { tone: countTone, label: lastRange('Rows rejected') }),
        {
          kind: 'bars',
          title: 'Ingest',
          sub: 'rows per min',
          span: 4,
          query: [m('vm.rows.ingested', 'rate', {}, [], 'ingested'), m('vm.rows.rejected', 'rate', {}, [], 'rejected')],
          select: (s) => scale(60)(pick(['ingested', 'ingested', blue], ['rejected', 'rejected', red])(s)),
        },
        {
          kind: 'lines',
          title: 'Query latency',
          sub: 'range queries, p50 / p90 / p99',
          span: 4,
          query: [vmQuantile('0.5', 'p50'), vmQuantile('0.9', 'p90'), vmQuantile('0.99', 'p99')],
          select: (s) => scale(1000)(pick(['p50', 'p50', blue], ['p90', 'p90', amber], ['p99', 'p99', red])(s)),
          format: formatMs,
        },
        {
          kind: 'bars',
          title: 'Range queries',
          sub: 'by result, every caller (the console’s stream and anyone else)',
          span: 4,
          query: [m('vm.requests', 'increase', { path: VM_RANGE }, [], 'requests'), m('vm.errors', 'increase', { path: VM_RANGE }, [], 'errors')],
          select: (s) => {
            const errors = new Map(s.find((x) => x.label === 'errors')?.points.map((p) => [p.t, p.v]))
            const requests = s.find((x) => x.label === 'requests')
            const ok = requests && { label: 'ok', color: blue, points: requests.points.map((p) => ({ t: p.t, v: Math.max(0, p.v - (errors.get(p.t) ?? 0)) })) }
            return [...(ok ? [ok] : []), ...pick(['errors', 'errors', red])(s)]
          },
        },
      ],
    },
    {
      title: 'freshness',
      mono: true,
      tag: 'is the data on the other pages current',
      tab: 'Freshness',
      widgets: [
        {
          kind: 'stream',
          source: 'metrics',
          title: 'Latest sample per metric',
          sub: 'age of the newest sample in the last 5 minutes, whatever the range; stale after 60 s when pushed, 120 s when scraped',
          span: 12,
          range: '5m',
          query: FRESHNESS.map(({ metric }) => m(metric, 'last', {}, [], metric)),
          rows: freshnessRows,
        },
      ],
    },
  ],
  note: 'Retention is VictoriaMetrics’ -retentionPeriod=800d in docker-compose.yml, a flag rather than a series. Disk used is the data size against data plus free space on its volume. Range queries and their latency count every caller of VictoriaMetrics, not only the console; the console’s own share is not told apart.',
}

export const INFRA_PAGES: Record<InfraPage, PageSpec> = { mongo, backend, bridge, kafka, minio, cron, edge, telemetry }

/** Sidebar path of each page (`layout/nav.ts`); Logs has its own page. */
export const INFRA_ROUTES: Record<string, InfraPage> = {
  '/infra/backend': 'backend',
  '/infra/mongodb': 'mongo',
  '/infra/bridge': 'bridge',
  '/infra/kafka': 'kafka',
  '/infra/minio': 'minio',
  '/infra/restock-cron': 'cron',
  '/infra/edge': 'edge',
  '/infra/telemetry': 'telemetry',
}

