import { describe, expect, it } from 'vitest'
import catalog from '@/api/__fixtures__/metrics-catalog.json'
import type { MetricQuery } from '@/api/metrics'
import { PALETTE, type Series } from '@/widgets/scale'
import {
  httpErrorRate,
  httpHits,
  httpLatency,
  latest,
  mongoLatency,
  mongoOps,
  rate,
  ratioSeries,
  successRate,
  sumSeries,
} from './presets'

const s = (label: string, points: [number, number][]): Series => ({
  label,
  color: '#000',
  points: points.map(([t, v]) => ({ t, v })),
})

const presets = [
  httpHits(),
  httpHits('/products/{id}', 'GET'),
  httpLatency(),
  httpLatency('/orders', 'POST'),
  httpErrorRate('/orders'),
  successRate('/orders', 'GET'),
  mongoOps(),
  mongoOps('orders'),
  mongoLatency(),
  mongoLatency('orders'),
]

describe('presets', () => {
  it('everyPresetUsesOnlyNamesTheRecordedCatalogueOffers', () => {
    const byName = new Map(catalog.metrics.map((m) => [m.name, m]))
    const queries: MetricQuery[] = [...presets.flatMap((p) => p.queries), rate('kafka.messages', { direction: 'out' }, ['topic'])]
    for (const q of queries) {
      const entry = byName.get(q.metric)
      expect(entry, q.metric).toBeDefined()
      expect(entry?.permitted_aggregations, `${q.metric} ${q.agg}`).toContain(q.agg)
      for (const d of [...Object.keys(q.filters ?? {}), ...(q.groupBy ?? [])]) expect(entry?.dimensions, `${q.metric} ${d}`).toContain(d)
    }
  })

  it('httpPresetsSendTheExpectedQueries', () => {
    expect(httpHits('/products/{id}', 'GET').queries).toEqual([
      { metric: 'http.server.duration', agg: 'increase', filters: { route: '/products/{id}', method: 'GET' }, groupBy: ['status_class'] },
    ])
    expect(httpHits().queries).toEqual([{ metric: 'http.server.duration', agg: 'increase', filters: {}, groupBy: ['status_class'] }])
    expect(httpLatency('/orders').queries).toEqual(
      ['p50', 'p95', 'p99'].map((agg) => ({ metric: 'http.server.duration', agg, filters: { route: '/orders' }, label: agg })),
    )
    expect(httpErrorRate('/orders').queries).toEqual(httpHits('/orders').queries)
    expect(successRate('/orders', 'GET').queries).toEqual(httpHits('/orders', 'GET').queries)
  })

  it('mongoPresetsSendTheExpectedQueries', () => {
    expect(mongoOps('orders').queries).toEqual([
      { metric: 'mongo.command.duration', agg: 'increase', filters: { collection: 'orders' }, groupBy: ['outcome'] },
    ])
    expect(mongoOps().queries[0].filters).toEqual({})
    // Without a collection the breakdown is by collection; within one, by command.
    expect(mongoLatency().queries).toEqual([{ metric: 'mongo.command.duration', agg: 'p95', filters: {}, groupBy: ['collection'] }])
    expect(mongoLatency('orders').queries).toEqual([
      { metric: 'mongo.command.duration', agg: 'p95', filters: { collection: 'orders' }, groupBy: ['command'] },
    ])
  })

  it('hitsAreStackedByStatusClassInOrder', () => {
    const out = httpHits().select([s('5xx', [[1, 1]]), s('2xx', [[1, 4]]), s('4xx', [[1, 2]]), s('3xx', [[1, 3]])])
    expect(out.map((x) => x.label)).toEqual(['2xx', '3xx', '4xx', '5xx'])
    const ops = mongoOps().select([s('error', []), s('ok', []), s('slow', [])])
    expect(ops.map((x) => x.label)).toEqual(['ok', 'slow', 'error'])
    expect(httpLatency().select([s('p50', [])])).toEqual([s('p50', [])])
  })

  it('httpErrorRateIsFivexxOverTotal', () => {
    const series = [
      s('2xx', [[1, 90], [2, 50], [3, 0], [4, 10]]),
      s('4xx', [[1, 5], [3, 0]]),
      s('5xx', [[1, 5], [2, 50], [3, 0]]),
    ]
    // t=4 has traffic but no 5xx sample: 0 %. t=3 has no traffic at all: missing, never 0 or NaN.
    expect(httpErrorRate().select(series)).toEqual([
      { label: '5xx %', color: PALETTE.red, points: [{ t: 1, v: 5 }, { t: 2, v: 50 }, { t: 4, v: 0 }] },
    ])
    expect(httpErrorRate().select([])).toEqual([{ label: '5xx %', color: PALETTE.red, points: [] }])
  })

  it('successRateIsOneMinusTheFivexxShareOverTheRange', () => {
    const series = [s('2xx', [[1, 97], [2, 98]]), s('5xx', [[1, 3], [2, 2]])]
    expect(successRate().select(series)).toEqual({ value: '97.5%', tone: 'warn' })
    expect(successRate().select([s('2xx', [[1, 10]])])).toEqual({ value: '100%', tone: 'ok' })
    expect(successRate().select([s('5xx', [[1, 10]])])).toEqual({ value: '0%', tone: 'error' })
    // No traffic: there is no rate to show, so no value rather than a made-up 100 %.
    expect(successRate().select([s('2xx', [[1, 0]])])).toBeUndefined()
    expect(successRate().select([])).toBeUndefined()
  })
})

describe('helpers', () => {
  it('rateBuildsACounterRateQuery', () => {
    expect(rate('bridge.messages')).toEqual({ metric: 'bridge.messages', agg: 'rate', filters: {}, groupBy: [] })
    expect(rate('bridge.messages', { flow: 'orders_in' }, ['result'])).toEqual({
      metric: 'bridge.messages',
      agg: 'rate',
      filters: { flow: 'orders_in' },
      groupBy: ['result'],
    })
  })

  it('latestIsTheLastPointOrUndefined', () => {
    expect(latest(s('a', [[1, 3], [2, 7]]))).toBe(7)
    expect(latest(s('a', []))).toBeUndefined()
    expect(latest(undefined)).toBeUndefined()
  })

  it('sumSeriesAddsPerTimestampOverTheUnion', () => {
    expect(sumSeries([s('a', [[2, 1], [1, 1]]), s('b', [[2, 2], [3, 4]])], { label: 'total', color: '#fff' })).toEqual({
      label: 'total',
      color: '#fff',
      points: [{ t: 1, v: 1 }, { t: 2, v: 3 }, { t: 3, v: 4 }],
    })
    expect(sumSeries([], { label: 'total', color: '#fff' }).points).toEqual([])
  })

  it('ratioSeriesDividesWhereTheDenominatorIsPositive', () => {
    const a = s('busy', [[1, 5], [2, 1], [4, 2]])
    const b = s('max', [[1, 10], [2, 0], [3, 10]])
    const as = { label: 'r', color: '#fff' }
    // A missing numerator is missing unless the caller says what it means.
    expect(ratioSeries(a, b, as).points).toEqual([{ t: 1, v: 0.5 }])
    expect(ratioSeries(a, b, { ...as, missingAs: 0 }).points).toEqual([{ t: 1, v: 0.5 }, { t: 3, v: 0 }])
    expect(ratioSeries(undefined, b, as).points).toEqual([])
    expect(ratioSeries(a, undefined, as).points).toEqual([])
  })
})
