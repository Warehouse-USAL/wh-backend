import { describe, expect, it } from 'vitest'
import { PALETTE } from '@/widgets/scale'
import hits from './__fixtures__/metrics-http-hits.json'
import p95 from './__fixtures__/metrics-http-p95.json'
import { type MetricsQueryResponse, colorFor, stepMs, toSeries } from './metrics'

const response = (series: MetricsQueryResponse['series'], metric = 'http.server.duration'): MetricsQueryResponse => ({ metric, unit: 'ms', step: '15s', series })

describe('toSeries', () => {
  it('mapsOneSeriesPerGroupInEpochMilliseconds', () => {
    const series = toSeries(hits, { metric: 'http.server.duration', agg: 'increase', groupBy: ['status_class'] })
    expect(series.map((s) => [s.label, s.color])).toEqual([
      ['2xx', PALETTE.blue],
      ['4xx', PALETTE.amber],
      ['5xx', PALETTE.red],
    ])
    expect(series[0].points.slice(0, 2)).toEqual([
      { t: 1792355880_000, v: 7 },
      { t: 1792355895_000, v: 9 },
    ])
  })

  it('labelsAnUngroupedSeriesWithTheQueryLabelOrTheMetric', () => {
    const [labelled] = toSeries(p95, { metric: 'http.server.duration', agg: 'p95', label: 'p95' })
    expect(labelled).toMatchObject({ label: 'p95', color: PALETTE.amber })
    // The recorded response has gaps (no requests in a bucket): they stay gaps.
    expect(labelled.points).toHaveLength(6)
    expect(toSeries(p95, { metric: 'http.server.duration', agg: 'p95' })[0].label).toBe('http.server.duration')
  })

  it('joinsGroupValuesInGroupByOrderAfterTheLabel', () => {
    const r = response([{ labels: { command: 'find', collection: 'orders' }, points: [] }], 'mongo.command.duration')
    expect(toSeries(r, { metric: 'mongo.command.duration', agg: 'p99', label: 'p99', groupBy: ['collection', 'command'] })[0].label).toBe('p99 orders find')
  })

  it('missingSamplesBecomeAnEmptySeriesNotNaN', () => {
    const r = response([
      { labels: { status_class: '5xx' }, points: [[1, 'NaN'], [2, null], [3], [4, 'Infinity']] },
      { labels: { status_class: '2xx' }, points: [[1, 'NaN'], [2, 3], [null, 4]] },
    ])
    const [none, some] = toSeries(r, { metric: 'http.server.duration', agg: 'increase', groupBy: ['status_class'] })
    expect(none).toEqual({ label: '5xx', color: PALETTE.red, points: [] })
    expect(some.points).toEqual([{ t: 2000, v: 3 }])
  })

  it('anEmptyResultIsNoSeries', () => {
    expect(toSeries(response([], 'm'), { metric: 'm', agg: 'rate' })).toEqual([])
  })
})

describe('colorFor', () => {
  it('givesKnownLabelsTheirMeaningAndOthersAStableColor', () => {
    expect([colorFor('2xx'), colorFor('3xx'), colorFor('4xx'), colorFor('5xx')]).toEqual([PALETTE.blue, PALETTE.gray, PALETTE.amber, PALETTE.red])
    expect([colorFor('ok'), colorFor('slow'), colorFor('error')]).toEqual([PALETTE.blue, PALETTE.amber, PALETTE.red])
    expect([colorFor('p50'), colorFor('p95'), colorFor('p99')]).toEqual([PALETTE.blue, PALETTE.amber, PALETTE.red])
    expect(colorFor('orders')).toBe(colorFor('orders'))
    expect(colorFor('orders')).toMatch(/^var\(--s-[a-z]+\)$/)
    expect(new Set(['orders', 'products', 'users', 'vehicles', 'zones'].map(colorFor)).size).toBeGreaterThan(2)
  })
})

describe('stepMs', () => {
  it('parsesTheBackendStepGrammar', () => {
    expect([stepMs('15s'), stepMs('1m'), stepMs('5m'), stepMs('2h'), stepMs('1d')]).toEqual([15_000, 60_000, 300_000, 7_200_000, 86_400_000])
  })
})
