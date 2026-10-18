import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { MetricQuery } from '@/api/metrics'
import catalog from '@/api/__fixtures__/metrics-catalog.json'
import { useLive } from '@/data/live'
import { type WsEvent, type WsFeed, useWsEvents } from '@/data/streamHooks'
import { type MetricState, useMetric } from '@/data/useMetric'
import type { Series } from '@/widgets/scale'
import WebSockets from './WebSockets'

vi.mock('@/data/streamHooks', () => ({ useStatus: vi.fn(), useLogs: vi.fn(), useWsEvents: vi.fn() }))
vi.mock('@/data/useMetric', async (importOriginal) => ({ ...(await importOriginal<object>()), useMetric: vi.fn() }))
// A fixed range and no tick: the page itself must not need a timer to stay current.
vi.mock('@/data/live', async (importOriginal) => ({ ...(await importOriginal<object>()), useLive: vi.fn() }))

const T0 = Date.parse('2026-10-18T14:00:00Z')
const series = (label: string, values: number[]): Series => ({ label, color: '#000', points: values.map((v, i) => ({ t: T0 + i * 15_000, v })) })
const state = (s: Series[], extra: Partial<MetricState> = {}): MetricState => ({ series: s, times: [], loading: false, refreshing: false, ...extra })
const ev = (at: string, kind: WsEvent['kind'], detail: string): WsEvent => ({ at, kind, detail })

const asked: MetricQuery[] = []
function stubMetrics(answer: (q: MetricQuery) => MetricState = () => state([])) {
  asked.length = 0
  vi.mocked(useMetric).mockImplementation((req) => {
    const q = (Array.isArray(req) ? req[0] : req) as MetricQuery
    asked.push(q)
    return answer(q)
  })
}

let events: Partial<Record<WsFeed, WsEvent[]>> = {}
const feedGroup = (path: string) => screen.getByRole('region', { name: path })
// The `feed` tag values WsActivity records.
const FEEDS = ['/ws/v1/orders', '/ws/v1/orders/{userId}', '/ws/v1/vehicles', '/ws/v1/stock/alerts']
const catalogNames = new Set(catalog.metrics.map((m) => m.name))

describe('WebSockets', () => {
  beforeEach(() => {
    events = {}
    vi.mocked(useLive).mockReturnValue({ live: true, range: '15m', toggle: vi.fn(), setRange: vi.fn() })
    vi.mocked(useWsEvents).mockImplementation((feed) => ({ data: events[feed] ?? [], loading: false }))
    stubMetrics()
  })
  afterEach(() => vi.useRealTimers())

  it('rendersOneGroupPerFeedWithFourWidgetsAndAnEventBuffer', () => {
    render(<WebSockets />)

    const titles = screen.getAllByRole('region').map((r) => within(r).getAllByRole('heading')[0].textContent)
    expect(titles).toEqual(FEEDS)
    for (const t of ['Clients', 'Messages pushed', 'Disconnects', /^Rejected handshakes/, 'Event buffer']) {
      expect(within(feedGroup('/ws/v1/orders')).getByText(t)).toBeInTheDocument()
    }
    expect(vi.mocked(useWsEvents).mock.calls.map(([f]) => f)).toEqual(expect.arrayContaining(['orders', 'user-orders', 'vehicles', 'stock-alerts']))
  })

  it('queriesSessionsAndMessagesPerFeedFromTheCatalogue', () => {
    render(<WebSockets />)

    for (const feed of FEEDS) {
      expect(asked).toContainEqual(expect.objectContaining({ metric: 'ws.sessions', filters: { feed } }))
      expect(asked).toContainEqual(expect.objectContaining({ metric: 'ws.messages', filters: { feed }, groupBy: ['result'] }))
    }
    for (const q of asked) expect(catalogNames).toContain(q.metric)
    const ws = catalog.metrics.filter((m) => m.name.startsWith('ws.'))
    for (const q of asked) {
      const m = ws.find((x) => x.name === q.metric)
      expect(m?.permitted_aggregations).toContain(q.agg)
      for (const k of [...Object.keys(q.filters ?? {}), ...(q.groupBy ?? [])]) expect(m?.dimensions).toContain(k)
    }
  })

  it('rejectedHandshakesTileCountsRejectionsOverTheRange', () => {
    stubMetrics((q) => {
      if (q.metric !== 'ws.messages') return state([series('sessions', [2])])
      if (q.filters?.feed === '/ws/v1/orders/{userId}') return state([series('pushed', [50]), series('closed', [2]), series('rejected', [2, 1])])
      return state([series('pushed', [50]), series('closed', [3])])
    })
    render(<WebSockets />)

    const users = within(feedGroup('/ws/v1/orders/{userId}'))
    expect(users.getByText('3')).toHaveClass('text-er')
    expect(users.getByText('Rejected handshakes, bad or missing token / user mismatch')).toBeInTheDocument()
    const vehicles = within(feedGroup('/ws/v1/vehicles'))
    expect(vehicles.getByText('0')).toHaveClass('text-ok')
    expect(vehicles.getByText('Rejected handshakes, bad or missing token / role not allowed')).toBeInTheDocument()
  })

  it('disconnectsShowOnlyClosedSessionsNeverRejectedHandshakes', () => {
    stubMetrics((q) => (q.metric === 'ws.messages' ? state([series('closed', [3]), series('rejected', [7])]) : state([])))
    render(<WebSockets />)

    const disconnects = within(feedGroup('/ws/v1/orders')).getByText('sessions closed').parentElement?.parentElement
    expect(disconnects).toHaveTextContent('closed')
    expect(disconnects).not.toHaveTextContent('rejected')
    // The 7 rejections are counted on their own tile instead.
    expect(within(feedGroup('/ws/v1/orders')).getByText('7')).toHaveClass('text-er')
  })

  it('emptyAndErrorStates', () => {
    stubMetrics((q) => (q.metric === 'ws.sessions' ? state([], { error: new ApiError(503, 'VM_DOWN', 'Metrics store unavailable') }) : state([])))
    render(<WebSockets />)

    const orders = feedGroup('/ws/v1/orders')
    expect(within(orders).getByText('Metrics store unavailable')).toBeInTheDocument()
    expect(within(orders).getAllByText('No data in this range')).toHaveLength(2)
    expect(within(orders).getByText('0')).toHaveClass('text-ok')
    expect(within(orders).getByText('No events yet')).toBeInTheDocument()
  })

  it('showsLoadingUntilTheFirstAnswer', () => {
    stubMetrics(() => state([], { loading: true }))
    vi.mocked(useWsEvents).mockReturnValue({ data: [], loading: true })
    render(<WebSockets />)

    expect(within(feedGroup('/ws/v1/orders')).getAllByRole('status', { name: 'Loading' })).toHaveLength(5)
  })

  it('showsTheStreamError', () => {
    vi.mocked(useWsEvents).mockReturnValue({ data: [], loading: false, error: new ApiError(500, 'X', 'Stream closed') })
    render(<WebSockets />)

    expect(within(feedGroup('/ws/v1/vehicles')).getByText('Stream closed')).toBeInTheDocument()
  })

  it('eventBufferRendersNewestFirst', () => {
    events.orders = [
      ev('2026-10-18T14:01:40.331Z', 'close', 'client went away, code 1001'),
      ev('2026-10-18T14:02:12.516Z', 'push', 'order.updated o_7f3a status ASSIGNED'),
      ev('2026-10-18T14:02:09.870Z', 'connect', 'client ana@wh.com'),
      ev('2026-10-18T14:01:20.044Z', 'reject', 'client asked for u_01, 403'),
    ]
    render(<WebSockets />)

    const buffer = within(feedGroup('/ws/v1/orders'))
    const rows = buffer.getAllByText(/order.updated|client/)
    expect(rows.map((r) => r.textContent)).toEqual([
      'order.updated o_7f3a status ASSIGNED',
      'client ana@wh.com',
      'client went away, code 1001',
      'client asked for u_01, 403',
    ])
    expect(buffer.getByText('push')).toHaveClass('text-blue')
    expect(buffer.getByText('connect')).toHaveClass('text-ok')
    expect(buffer.getByText('close')).toHaveClass('text-wn')
    expect(buffer.getByText('reject')).toHaveClass('text-er')
    expect(buffer.getByText('Live')).toBeInTheDocument()
  })

  it('eventBufferRendersPushedEventsWithoutPolling', () => {
    vi.useFakeTimers()
    const fetchSpy = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchSpy)
    events.vehicles = [ev('2026-10-18T14:02:10.220Z', 'push', 'vehicle v_12 telemetry battery 78')]
    const { rerender } = render(<WebSockets />)
    const vehicles = () => within(feedGroup('/ws/v1/vehicles'))
    expect(vehicles().getAllByText(/^vehicle v_/)).toHaveLength(1)

    // The hook pushes a new event: it appears on top, with no timer or request behind it.
    events.vehicles = [ev('2026-10-18T14:02:13.120Z', 'push', 'vehicle v_09 state IDLE'), ...events.vehicles]
    rerender(<WebSockets />)

    expect(vehicles().getAllByText(/^vehicle v_/).map((r) => r.textContent)).toEqual(['vehicle v_09 state IDLE', 'vehicle v_12 telemetry battery 78'])
    expect(vi.getTimerCount()).toBe(0)
    vi.advanceTimersByTime(60_000)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
