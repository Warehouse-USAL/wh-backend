import { describe, expect, it } from 'vitest'
import { jsonResponse, mockFetch } from '@/test/http'
import { PALETTE } from '@/widgets/scale'
import hits from './__fixtures__/metrics-http-hits.json'
import p95 from './__fixtures__/metrics-http-p95.json'
import { colorFor, queryMetric } from './metrics'

const span = { from: '2026-10-18T20:33:00Z', to: '2026-10-18T20:38:00Z', step: '15s' }

describe('queryMetric', () => {
  it('sendsTheSnakeCaseBodyAndMapsOneSeriesPerGroup', async () => {
    const fetchMock = mockFetch(jsonResponse(200, hits))
    const series = await queryMetric({
      metric: 'http.server.duration',
      agg: 'increase',
      filters: { route: '/products', method: 'GET' },
      groupBy: ['status_class'],
      ...span,
    })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/metrics/query')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      metric: 'http.server.duration',
      from: span.from,
      to: span.to,
      step: '15s',
      filters: { route: '/products', method: 'GET' },
      group_by: ['status_class'],
      agg: 'increase',
    })
    expect(series.map((s) => [s.label, s.color])).toEqual([
      ['2xx', PALETTE.blue],
      ['4xx', PALETTE.amber],
      ['5xx', PALETTE.red],
    ])
    // Epoch seconds become epoch milliseconds.
    expect(series[0].points.slice(0, 2)).toEqual([
      { t: 1792355880_000, v: 7 },
      { t: 1792355895_000, v: 9 },
    ])
  })

  it('labelsAnUngroupedSeriesWithTheQueryLabelOrTheMetric', async () => {
    mockFetch(jsonResponse(200, p95), jsonResponse(200, p95))
    const [labelled] = await queryMetric({ metric: 'http.server.duration', agg: 'p95', label: 'p95', ...span })
    expect(labelled).toMatchObject({ label: 'p95', color: PALETTE.amber })
    // The recorded response has gaps (no requests in a bucket): they stay gaps.
    expect(labelled.points).toHaveLength(6)
    const [bare] = await queryMetric({ metric: 'http.server.duration', agg: 'p95', ...span })
    expect(bare.label).toBe('http.server.duration')
  })

  it('joinsGroupValuesInGroupByOrderAfterTheLabel', async () => {
    mockFetch(
      jsonResponse(200, {
        metric: 'mongo.command.duration',
        unit: 'ms',
        step: '15s',
        series: [{ labels: { command: 'find', collection: 'orders' }, points: [] }],
      }),
    )
    const [s] = await queryMetric({ metric: 'mongo.command.duration', agg: 'p99', label: 'p99', groupBy: ['collection', 'command'], ...span })
    expect(s.label).toBe('p99 orders find')
  })

  it('missingSamplesBecomeAnEmptySeriesNotNaN', async () => {
    mockFetch(
      jsonResponse(200, {
        metric: 'http.server.duration',
        unit: 'ms',
        step: '15s',
        series: [
          { labels: { status_class: '5xx' }, points: [[1, 'NaN'], [2, null], [3], [4, 'Infinity']] },
          { labels: { status_class: '2xx' }, points: [[1, 'NaN'], [2, 3], [null, 4]] },
        ],
      }),
    )
    const [none, some] = await queryMetric({ metric: 'http.server.duration', agg: 'increase', groupBy: ['status_class'], ...span })
    expect(none).toEqual({ label: '5xx', color: PALETTE.red, points: [] })
    expect(some.points).toEqual([{ t: 2000, v: 3 }])
  })

  it('anEmptyResultIsNoSeries', async () => {
    mockFetch(jsonResponse(200, { metric: 'm', unit: '1', step: '15s', series: [] }))
    expect(await queryMetric({ metric: 'm', agg: 'rate', ...span })).toEqual([])
  })
})

describe('colorFor', () => {
  it('givesKnownLabelsTheirMeaningAndOthersAStableColor', () => {
    expect([colorFor('2xx'), colorFor('3xx'), colorFor('4xx'), colorFor('5xx')]).toEqual([PALETTE.blue, PALETTE.gray, PALETTE.amber, PALETTE.red])
    expect([colorFor('ok'), colorFor('slow'), colorFor('error')]).toEqual([PALETTE.blue, PALETTE.amber, PALETTE.red])
    expect([colorFor('p50'), colorFor('p95'), colorFor('p99')]).toEqual([PALETTE.blue, PALETTE.amber, PALETTE.red])
    expect(colorFor('orders')).toBe(colorFor('orders'))
    expect(colorFor('orders')).toMatch(/^#[0-9a-f]{6}$/)
    expect(new Set(['orders', 'products', 'users', 'vehicles', 'zones'].map(colorFor)).size).toBeGreaterThan(2)
  })
})
