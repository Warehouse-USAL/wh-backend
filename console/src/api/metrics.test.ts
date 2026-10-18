import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { jsonResponse, mockFetch } from '@/test/http'
import { PALETTE } from '@/widgets/scale'
import hits from './__fixtures__/metrics-http-hits.json'
import p95 from './__fixtures__/metrics-http-p95.json'
import { clearMetricAnswers, colorFor, queryMetric, stepMs } from './metrics'

const span = { from: '2026-10-18T20:33:00Z', to: '2026-10-18T20:38:00Z', step: '15s' }

beforeEach(() => clearMetricAnswers())

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
    expect(colorFor('orders')).toMatch(/^var\(--s-[a-z]+\)$/)
    expect(new Set(['orders', 'products', 'users', 'vehicles', 'zones'].map(colorFor)).size).toBeGreaterThan(2)
  })
})

type Pending = { body: string; signal: AbortSignal; resolve(r: Response): void }

/** A fetch that answers only when told, rejecting with AbortError when its signal aborts. */
function deferredFetch() {
  const pending: Pending[] = []
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(
    (_, init) =>
      new Promise((resolve, reject) => {
        const signal = init?.signal as AbortSignal
        pending.push({ body: init?.body as string, signal, resolve })
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, pending }
}

const answer = (v: number) => jsonResponse(200, { metric: 'm', unit: '1', step: '15s', series: [{ labels: {}, points: [[1, v]] }] })
const q = (metric = 'kafka.messages') => ({ metric, agg: 'rate' as const, ...span })
const flush = () => new Promise((r) => setTimeout(r, 0))

describe('shared requests', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true, now: Date.UTC(2026, 9, 18, 20, 38) }))
  afterEach(() => vi.useRealTimers())

  it('identicalBodiesShareOneFetch', async () => {
    const { fetchMock, pending } = deferredFetch()
    const all = Promise.all([queryMetric(q()), queryMetric(q()), queryMetric(q())])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    pending[0].resolve(answer(7))
    expect((await all).map(([s]) => s.points[0].v)).toEqual([7, 7, 7])
  })

  it('onlyTheLastSubscriberLeavingAbortsTheSharedFetch', async () => {
    const { pending } = deferredFetch()
    const [a, b] = [new AbortController(), new AbortController()]
    const first = queryMetric(q(), a.signal)
    const second = queryMetric(q(), b.signal)
    a.abort()
    await expect(first).rejects.toMatchObject({ name: 'AbortError' })
    await flush()
    expect(pending[0].signal.aborted).toBe(false)
    b.abort()
    await expect(second).rejects.toMatchObject({ name: 'AbortError' })
    await flush()
    expect(pending[0].signal.aborted).toBe(true)
    // An already aborted caller never joins.
    await expect(queryMetric(q(), a.signal)).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('aSubscriberHandOverInTheSameTaskKeepsTheFetch', async () => {
    const { fetchMock, pending } = deferredFetch()
    const old = new AbortController()
    queryMetric(q(), old.signal).catch(() => {})
    old.abort()
    const next = queryMetric(q())
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(pending[0].signal.aborted).toBe(false)
    pending[0].resolve(answer(3))
    expect((await next)[0].points[0].v).toBe(3)
  })

  it('reusesAnAnswerForUpToOneStepAndNotAnError', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(503, { error: { code: 'METRICS_UNAVAILABLE', message: 'down' } }))
      .mockResolvedValueOnce(answer(1))
      .mockResolvedValueOnce(answer(2))
    vi.stubGlobal('fetch', fetchMock)
    await expect(queryMetric(q())).rejects.toMatchObject({ code: 'METRICS_UNAVAILABLE' })
    expect((await queryMetric(q()))[0].points[0].v).toBe(1)
    vi.advanceTimersByTime(stepMs('15s') - 100)
    expect((await queryMetric(q()))[0].points[0].v).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(200)
    expect((await queryMetric(q()))[0].points[0].v).toBe(2)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('runsAtMostSixAtOnceInOrderAndDropsQueuedOnesNobodyWants', async () => {
    const { fetchMock, pending } = deferredFetch()
    const leaving = new AbortController()
    const calls = Array.from({ length: 9 }, (_, i) => queryMetric(q(`m${i}`), i === 7 ? leaving.signal : undefined).catch((e: Error) => e.name))
    expect(fetchMock).toHaveBeenCalledTimes(6)
    leaving.abort()
    await flush()
    pending[0].resolve(answer(0))
    await flush()
    pending[1].resolve(answer(1))
    await flush()
    // m6 and m8 started in order; m7 was dropped without a request.
    expect(pending.map((p) => JSON.parse(p.body).metric)).toEqual(['m0', 'm1', 'm2', 'm3', 'm4', 'm5', 'm6', 'm8'])
    expect(await calls[7]).toBe('AbortError')
    for (const p of pending.slice(2)) p.resolve(answer(9))
    expect((await Promise.all(calls)).filter((r) => r === 'AbortError')).toHaveLength(1)
  })
})

describe('stepMs', () => {
  it('readsEveryUnit', () => {
    expect(['10s', '1m', '1h', '7d'].map(stepMs)).toEqual([10_000, 60_000, 3_600_000, 604_800_000])
  })
})
