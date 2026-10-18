import { describe, expect, it } from 'vitest'
import catalog from '@/api/__fixtures__/metrics-catalog.json'
import type { MetricQuery } from '@/api/metrics'
import type { Series } from '@/widgets/scale'
import { type ChartSpec, type Ctx, type MetricStreamSpec, type PageSpec, type TileSpec, type WidgetSpec, metricQueries } from './infra/spec'
import { INFRA_PAGES, INFRA_ROUTES, MONGO_COLLECTIONS } from './infraPresets'

const T0 = Date.parse('2026-10-18T14:00:00Z')
const ctx: Ctx = { range: '15m', now: T0 }

/** A series with one point per value, 15 s apart, ending at `end`; `undefined` leaves a gap. */
function seq(label: string, values: (number | undefined)[], end = T0): Series {
  const points = values.flatMap((v, i) => (v === undefined ? [] : [{ t: end - (values.length - 1 - i) * 15_000, v }]))
  return { label, color: '#000', points }
}

/** Tag values the backend reports for each grouped dimension (a sample of them). */
const VALUES: Record<string, string[]> = {
  result: ['ok', 'retry', 'dropped', 'error'],
  outcome: ['ok', 'slow', 'error'],
  state: ['busy', 'max', 'current', 'in_use', 'available'],
  code: ['200', '304', '401', '403', '502'],
  collection: ['orders', 'users'],
  gc: ['G1 Young Generation'],
  topic: ['orders', 'order.completed'],
}

/** What the backend would answer for a query: one seeded series per group, labelled as `queryMetric` does. */
function answerFor(q: MetricQuery): Series[] {
  const groups = (q.groupBy ?? []).reduce<string[][]>((acc, key) => acc.flatMap((g) => (VALUES[key] ?? ['x']).map((v) => [...g, v])), [[]])
  return groups.map((values, i) => {
    const label = [q.label, ...values].filter(Boolean).join(' ') || q.metric
    const base = label === 'max' ? 200 : 5 + i
    return seq(label, Array.from({ length: 6 }, (_, k) => base + ((k * 7 + i * 3) % 5)), T0 - 5_000)
  })
}

const widgets = (page: PageSpec) => page.groups.flatMap((g) => g.widgets)
const find = <W extends WidgetSpec>(page: PageSpec, title: string, group?: string) => {
  const pool = group ? (page.groups.find((g) => g.title === group)?.widgets ?? []) : widgets(page)
  const found = pool.find((w) => w.title === title)
  if (!found) throw new Error(`no widget "${title}" on ${page.title}`)
  return found as W
}

type Descriptor = { name: string; dimensions: string[]; permitted_aggregations: string[] }
const CATALOG = new Map((catalog.metrics as Descriptor[]).map((d) => [d.name, d]))

/** Problems of each query against the catalogue fixture (`GET /metrics/catalog`). */
function catalogueProblems(queries: MetricQuery[]): string[] {
  return queries.flatMap((q) => {
    const d = CATALOG.get(q.metric)
    if (!d) return [`unknown metric ${q.metric}`]
    const dims = [...Object.keys(q.filters ?? {}), ...(q.groupBy ?? [])]
    return [
      ...(d.permitted_aggregations.includes(q.agg) ? [] : [`${q.metric}: aggregation ${q.agg} not permitted`]),
      ...dims.filter((k) => !d.dimensions.includes(k)).map((k) => `${q.metric}: unknown dimension ${k}`),
    ]
  })
}

describe('INFRA_PAGES', () => {
  it('everyPresetMetricExistsInTheCatalogueFixture', () => {
    const all = Object.values(INFRA_PAGES).flatMap(metricQueries)
    expect(all.length).toBeGreaterThan(80)
    expect(catalogueProblems(all)).toEqual([])

    // The check bites: a typo in a name, a tag or an aggregation is reported.
    const typos: MetricQuery[] = [
      { metric: 'bridge.mesages', agg: 'rate' },
      { metric: 'bridge.messages', agg: 'rate', filters: { flwo: 'orders_in' } },
      { metric: 'bridge.messages', agg: 'rate', groupBy: ['reslt'] },
      { metric: 'job.run', agg: 'p95' },
    ]
    expect(catalogueProblems(typos)).toEqual([
      'unknown metric bridge.mesages',
      'bridge.messages: unknown dimension flwo',
      'bridge.messages: unknown dimension reslt',
      'job.run: aggregation p95 not permitted',
    ])
  })

  it('everyWidgetDerivesItsPanelFromSampleAnswers', () => {
    for (const page of Object.values(INFRA_PAGES)) {
      for (const w of widgets(page)) {
        const answer = Array.isArray(w.query) ? w.query.flatMap(answerFor) : []
        const where = `${page.title} / ${w.title}`
        if (typeof w.sub === 'function') expect(w.sub(ctx, answer), where).toEqual(expect.any(String))
        if (w.kind === 'bars' || w.kind === 'lines') {
          const plotted = w.select ? w.select(answer, ctx) : answer
          expect(plotted.some((s) => s.points.length > 0), where).toBe(true)
          expect(plotted.flatMap((s) => s.points).every((p) => Number.isFinite(p.v) && p.v >= 0), where).toBe(true)
          if (w.format) expect(w.format(12.5), where).toEqual(expect.any(String))
        } else if (w.kind === 'tile') {
          expect(w.value(answer, ctx), where).toMatchObject({ value: expect.any(String), tone: expect.stringMatching(/^(ok|warn|error)$/) })
        } else if (w.kind === 'toplist') {
          expect(w.rows(answer, ctx).length, where).toBeGreaterThan(0)
          expect(w.format(3), where).toEqual(expect.any(String))
        } else if (w.kind === 'stream' && w.source === 'metrics') {
          expect(w.rows(answer, ctx).length, where).toBeGreaterThan(0)
        }
      }
    }
  })

  it('noWidgetInventsAValueWithoutSamples', () => {
    for (const page of Object.values(INFRA_PAGES)) {
      for (const w of widgets(page)) {
        const where = `${page.title} / ${w.title}`
        if (w.kind === 'bars' || w.kind === 'lines') expect((w.select?.([], ctx) ?? []).flatMap((s) => s.points), where).toEqual([])
        if (w.kind === 'tile' && w.query.length) expect(w.value([], ctx), where).toBeUndefined()
        if (w.kind === 'toplist') expect(w.rows([], ctx), where).toEqual([])
        if (w.kind === 'stream' && w.source === 'metrics') expect(w.rows([], ctx).every((r) => r.tag === 'none' && r.tone === 'error'), where).toBe(true)
        if (w.kind === 'stream' && w.source === 'logs') expect(w.rows([]), where).toEqual([])
      }
    }
  })

  it('bridgeExchangesShowLevelMessageAndRequest', () => {
    const exchanges = find(INFRA_PAGES.bridge, 'Exchanges', 'Exchanges')
    if (exchanges.kind !== 'stream' || exchanges.source !== 'logs') throw new Error('not a log stream')
    const rows = exchanges.rows([
      { time: '2026-10-18T14:02:11.417Z', level: 'warn', service: 'bridge', message: 'backend unavailable, retrying', requestId: 'r7f3a' },
      { time: null, level: null, service: 'bridge', message: null, requestId: null },
    ])
    expect(rows.map((r) => [r.tag, r.tone, r.text])).toEqual([
      ['warn', 'warn', 'backend unavailable, retrying req=r7f3a'],
      ['-', undefined, ''],
    ])
    expect(rows[1].time).toBe('-')
  })

  it('routesEveryInfrastructurePageButLogs', () => {
    expect(INFRA_ROUTES).toEqual({
      '/infra/backend': 'backend',
      '/infra/mongodb': 'mongo',
      '/infra/bridge': 'bridge',
      '/infra/kafka': 'kafka',
      '/infra/minio': 'minio',
      '/infra/restock-cron': 'cron',
      '/infra/edge': 'edge',
      '/infra/telemetry': 'telemetry',
    })
    for (const page of Object.values(INFRA_PAGES)) {
      expect(page.crumb).toBe('Infrastructure')
      // Every tab lands on a group, or on the page top when the board puts it in the same group.
      expect(page.groups.filter((g) => g.tab).map((g) => g.tab)).toEqual(expect.arrayContaining([page.tabs[0]]))
    }
  })

  it('bridgePresetUsesTheThreeFlowTags', () => {
    const flows = new Set(metricQueries(INFRA_PAGES.bridge).flatMap((q) => (q.filters?.flow ? [q.filters.flow] : [])))
    expect([...flows].sort()).toEqual(['orders_in', 'orders_out', 'vehicles_in'])
    const metrics = new Set(metricQueries(INFRA_PAGES.bridge).map((q) => q.metric))
    expect([...metrics].sort()).toEqual(['bridge.callback.duration', 'bridge.consumer.lag', 'bridge.messages'])
    // One group per flow, and the exchanges stream reads the bridge's own logs.
    const titles = INFRA_PAGES.bridge.groups.map((g) => g.title)
    expect(titles).toEqual(['Link health', 'Backend to Infra', 'POST /infra/orders/{orderId}/status', 'POST /infra/vehicles/{vehicleId}/telemetry', 'Exchanges'])
    expect(find(INFRA_PAGES.bridge, 'Exchanges', 'Exchanges').query).toEqual({ service: 'bridge', limit: 50 })
  })

  it('bridgeMessagesAreSplitByResultAndShareCountsOnlySettledMessages', () => {
    const messages = find<ChartSpec>(INFRA_PAGES.bridge, 'Messages', 'Backend to Infra')
    expect(messages.query).toEqual([{ metric: 'bridge.messages', agg: 'increase', filters: { flow: 'orders_out' }, groupBy: ['result'] }])
    const answer = [seq('error', [1, undefined]), seq('ok', [8, 9]), seq('retry', [5, 5]), seq('dropped', [1, 1])]
    expect(messages.select?.(answer, ctx).map((s) => s.label)).toEqual(['confirmed', 'retry', 'dropped', 'failed'])
    // Retries are attempts, not messages: 17 confirmed of 20 settled.
    const confirmed = find<TileSpec>(INFRA_PAGES.bridge, 'Confirmed').value(answer, ctx)
    expect(confirmed).toMatchObject({ value: '85%', tone: 'error' })
    const rate = find<ChartSpec>(INFRA_PAGES.bridge, 'Error rate', 'Backend to Infra').select?.(answer, ctx)
    expect(rate?.[0].points.map((p) => +p.v.toFixed(1))).toEqual([20, 10])
    expect(find<TileSpec>(INFRA_PAGES.bridge, 'Confirmed').value([seq('retry', [3])], ctx)).toBeUndefined()
  })

  it('mongoRequestsChartHasOkSlowAndFailed', () => {
    const page = INFRA_PAGES.mongo
    expect(MONGO_COLLECTIONS).toEqual(['orders', 'products', 'positions', 'receptions', 'restock_orders', 'zones', 'lines', 'vehicles', 'users'])
    expect(page.groups.map((g) => g.title)).toEqual(['Overview', ...MONGO_COLLECTIONS])
    for (const name of MONGO_COLLECTIONS) {
      const requests = find<ChartSpec>(page, 'Requests', name)
      expect(requests.kind).toBe('bars')
      expect(requests.query).toEqual([{ metric: 'mongo.command.duration', agg: 'increase', filters: { collection: name }, groupBy: ['outcome'] }])
      expect(find<ChartSpec>(page, 'Latency', name).query.map((q) => q.agg)).toEqual(['p50', 'p95', 'p99'])
    }
    const requests = find<ChartSpec>(page, 'Requests', 'orders')
    const answer = [seq('error', [1, 2]), seq('slow', [3, 1]), seq('ok', [20, 30])]
    const plotted = requests.select?.(answer, ctx) ?? []
    expect(plotted.map((s) => s.label)).toEqual(['ok', 'slow', 'failed'])
    expect(plotted[2].points).toEqual(answer[0].points)
    // Slow commands succeeded: only failures count against the rate.
    const rate = find<ChartSpec>(page, 'Error rate', 'orders').select?.(answer, ctx)
    expect(rate?.[0].points.map((p) => +p.v.toFixed(2))).toEqual([4.17, 6.06])
    expect(find<TileSpec>(page, 'Ops OK', 'orders').value(answer, ctx)).toMatchObject({ value: '94.74%', tone: 'error' })
    expect(find<TileSpec>(page, 'Ops OK', 'orders').value([], ctx)).toBeUndefined()
  })

  it('mongoOverviewRanksCollections', () => {
    const lists = INFRA_PAGES.mongo.groups[0].widgets
    expect(lists.map((w) => [w.kind, w.title])).toEqual([
      ['toplist', 'Requests'],
      ['toplist', 'Latency'],
      ['toplist', 'Failures'],
    ])
    const [requests, latency, failures] = lists as Extract<WidgetSpec, { kind: 'toplist' }>[]
    const answer = [seq('orders', [300, 600]), seq('users', [15, undefined]), seq('zones', [0, 0])]
    // Per minute over the 15 minute range; a collection without traffic is not ranked.
    expect(requests.rows(answer, ctx)).toEqual([
      { name: 'orders', value: 60 },
      { name: 'users', value: 1 },
    ])
    expect(latency.rows([seq('lines', [3, 9]), seq('users', [])], ctx)).toEqual([{ name: 'lines', value: 9 }])
    expect(failures.rows([seq('orders', [2, 1])], ctx)).toEqual([{ name: 'orders', value: 3 }])
    expect(typeof failures.sub === 'function' && failures.sub(ctx, [])).toBe('last 15m')
  })

  it('backendActiveRequestsAndUptimePanelsHaveSources', () => {
    const page = INFRA_PAGES.backend
    expect(find(page, 'Active requests').query).toEqual([{ metric: 'http.server.active', agg: 'max', filters: {}, groupBy: [], label: 'active requests' }])
    expect(find(page, 'Uptime')).toMatchObject({ kind: 'status', query: { service: 'backend', show: 'uptime' } })
    expect(page.groups[0].widgets.map((w) => w.kind === 'status' && w.query)).toEqual([
      { service: 'backend', show: 'state' },
      { service: 'backend', show: 'uptime' },
      { service: 'mongodb', show: 'state' },
      { service: 'redpanda', show: 'state' },
    ])
  })

  it('backendShowsConnectionSaturationBecauseTomcatHasNoRejectedCount', () => {
    const page = INFRA_PAGES.backend
    expect(widgets(page).some((w) => /rejected/i.test(w.title))).toBe(false)
    const saturation = find<TileSpec>(page, 'Connection saturation')
    expect(saturation.query).toEqual([{ metric: 'tomcat.connections', agg: 'max', filters: {}, groupBy: ['state'] }])
    expect(saturation.value([seq('current', [10, 50]), seq('max', [100, 100])], ctx)).toMatchObject({
      value: '50%',
      tone: 'ok',
      label: expect.stringMatching(/no rejected-connections count/),
      spark: [10, 50],
    })
    expect(saturation.value([seq('current', [9]), seq('max', [10])], ctx)?.tone).toBe('warn')
    expect(saturation.value([seq('current', [10]), seq('max', [10])], ctx)?.tone).toBe('error')
    expect(saturation.value([seq('current', [10])], ctx)).toBeUndefined()

    const busy = find<ChartSpec>(page, 'Busy threads')
    const answer = [seq('busy', [4, 6]), seq('max', [200, 200])]
    expect(busy.select?.(answer, ctx).map((s) => s.label)).toEqual(['busy threads'])
    expect(typeof busy.sub === 'function' && busy.sub(ctx, answer)).toBe('of 200')
    expect(typeof busy.sub === 'function' && busy.sub(ctx, [])).toBe('busy')
    const pool = find<ChartSpec>(page, 'Connections', 'mongo connection pool')
    expect(typeof pool.sub === 'function' && pool.sub(ctx, [seq('in_use', [2]), seq('available', [5])])).toBe('in use of 7 open')
    expect(typeof pool.sub === 'function' && pool.sub(ctx, [])).toBe('in use')
    expect(find<ChartSpec>(page, 'Heap').select?.([seq('heap used, MB', [5e8])], ctx)[0].points[0].v).toBe(500)
  })

  it('cronPageDerivesNextRunFromTheSchedule', () => {
    const next = find<TileSpec>(INFRA_PAGES.cron, 'Next run')
    expect(next.query).toEqual([])
    const local = new Date(2026, 9, 18, 14, 2)
    const value = next.value([], { ...ctx, now: local.getTime() })
    // The tile shows the run in the browser's time; the schedule itself is UTC.
    const run = new Date(Date.UTC(2026, 9, 19, 6, 0))
    const sameDay = run.getDate() === local.getDate()
    const hhmm = `${String(run.getHours()).padStart(2, '0')}:${String(run.getMinutes()).padStart(2, '0')}`
    if (local.getTimezoneOffset() === 0) expect(value?.value).toBe('06:00 tomorrow')
    expect(value).toMatchObject({ value: `${hhmm} ${sameDay ? 'today' : 'tomorrow'}`, tone: 'ok' })
    expect(value?.label).toMatch(/0 6 \* \* \*.*UTC/)
  })

  it('cronPageShowsSuggestionsPerRun', () => {
    const page = INFRA_PAGES.cron
    const suggestions = find<ChartSpec>(page, 'Suggestions')
    expect(suggestions.kind).toBe('bars')
    expect(suggestions.query).toEqual([{ metric: 'job.run', agg: 'last', filters: { job: 'restock', field: 'suggestions' }, groupBy: [], label: 'suggestions' }])
    expect(find<ChartSpec>(page, 'Duration').query[0].filters).toEqual({ job: 'restock', field: 'duration_ms' })

    const yesterday = T0 - 86_400_000 + 600_000
    const runs = [seq('ok', [1400], T0 - 60_000), seq('error', [120], yesterday)]
    expect(find<TileSpec>(page, 'Last run').value(runs, ctx)).toMatchObject({ value: 'OK', tone: 'ok' })
    expect(find<TileSpec>(page, 'Last run').value([seq('error', [120])], ctx)).toMatchObject({ value: 'Failed', tone: 'error' })
    expect(find<TileSpec>(page, 'Last duration').value(runs, ctx)).toMatchObject({ value: '1.4 s' })
    expect(find<TileSpec>(page, 'Runs ok').value(runs, ctx)).toMatchObject({ value: '50%', tone: 'error', label: 'Runs ok, last 15m' })
    expect(find<TileSpec>(page, 'Last run').value([], ctx)).toBeUndefined()
    // One bar per run, whatever its duration.
    const bars = find<ChartSpec>(page, 'Runs').select?.(runs, ctx) ?? []
    expect(bars.map((s) => [s.label, s.points.map((p) => p.v)])).toEqual([
      ['ok', [1]],
      ['failed', [1]],
    ])

    const recent = find<MetricStreamSpec>(page, 'Runs', 'recent runs')
    const answer = [
      seq('duration ok', [1400], T0 - 60_000),
      seq('suggestions ok', [12], T0 - 60_000),
      seq('duration error', [120], yesterday),
      seq('suggestions error', [0], yesterday),
    ]
    const rows = recent.rows(answer, ctx)
    expect(rows.map((r) => [r.tag, r.tone, r.text])).toEqual([
      ['ok', 'ok', '12 suggestions, 1.4 s'],
      ['failed', 'error', '0 suggestions, 120 ms'],
    ])
    expect(rows[1].time).toMatch(/^yesterday \d\d:\d\d$/)
    expect(recent.rows([seq('duration ok', [5])], ctx)[0].text).toBe('5 ms')
  })

  it('telemetryFreshnessFlagsAStaleSource', () => {
    const fresh = find<MetricStreamSpec>(INFRA_PAGES.telemetry, 'Latest sample per metric')
    expect(fresh.query.every((q) => q.agg === 'last' && q.label === q.metric)).toBe(true)
    const answer = [seq('jvm.heap.used', [1, 2], T0 - 20_000), seq('bridge.consumer.lag', [0, undefined, undefined], T0 - 40_000)]
    const rows = fresh.rows(answer, ctx)
    const row = (metric: string) => rows.find((r) => r.text === metric)
    expect(row('jvm.heap.used')).toMatchObject({ time: 'backend', tag: '20 s', tone: 'ok' })
    expect(row('bridge.consumer.lag')).toMatchObject({ time: 'bridge', tag: '1 min', tone: 'warn' })
    // A metric without a sample in the range is flagged too, never shown as fresh.
    expect(row('minio.objects')).toMatchObject({ time: 'minio', tag: 'none', tone: 'error' })
    expect(rows).toHaveLength(fresh.query.length)
    // Exactly 60 s is still current; the age keeps growing with the clock.
    expect(fresh.rows([seq('jvm.heap.used', [1], T0 - 60_000)], ctx)[0].tone).toBe('ok')
    expect(fresh.rows([seq('jvm.heap.used', [1], T0 - 60_000)], { ...ctx, now: T0 + 1000 })[0].tone).toBe('warn')
  })

  it('telemetryDiskAndQueryLatencyReadTheScrapedSeries', () => {
    const page = INFRA_PAGES.telemetry
    const disk = find<TileSpec>(page, 'Disk used')
    expect(disk.query.map((q) => [q.metric, q.agg])).toEqual([
      ['vm.disk.used', 'count'],
      ['vm.disk.free', 'last'],
    ])
    expect(disk.value([seq('used', [3e9]), seq('free', [7e9])], ctx)).toMatchObject({ value: '30%', tone: 'ok', label: 'Disk used, 3 GB of data' })
    expect(disk.value([seq('used', [9.5e9]), seq('free', [0.5e9])], ctx)?.tone).toBe('error')
    expect(disk.value([seq('used', [3e9])], ctx)).toBeUndefined()
    const latency = find<ChartSpec>(page, 'Query latency')
    expect(latency.query.map((q) => q.filters?.quantile)).toEqual(['0.5', '0.9', '0.99'])
    expect(latency.select?.([seq('p50', [0.012])], ctx)[0].points[0].v).toBe(12)
  })

  it('edgeReadsCaddyByStatusCode', () => {
    const page = INFRA_PAGES.edge
    expect(page.empty).toMatch(/^Only exists in production/)
    const requests = find<ChartSpec>(page, 'Hits')
    const answer = [seq('200', [90, 80]), seq('204', [5, 5]), seq('401', [3, 2]), seq('403', [1, 1]), seq('502', [1, 2])]
    expect(requests.select?.(answer, ctx).map((s) => [s.label, s.points.map((p) => p.v)])).toEqual([
      ['2xx', [95, 85]],
      ['4xx', [4, 3]],
      ['5xx', [1, 2]],
    ])
    expect(find<TileSpec>(page, '401 and 403').value(answer, ctx)).toMatchObject({ value: '3.68%', tone: 'warn' })
    expect(find<TileSpec>(page, 'Upstream errors').value([seq('errors', [1]), seq('requests', [200])], ctx)).toMatchObject({ value: '0.5%', tone: 'ok' })
    expect(find<TileSpec>(page, 'Caddy').value([], ctx)).toBeUndefined()
  })
})
