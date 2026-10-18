import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { MetricQuery } from '@/api/metrics'
import type { PageSpec } from '@/data/infra/spec'
import { INFRA_PAGES } from '@/data/infraPresets'
import { LiveProvider } from '@/data/live'
import { useLogs, useStatus } from '@/data/streamHooks'
import { type MetricState, useMetric } from '@/data/useMetric'
import type { Series } from '@/widgets/scale'
import PresetPage from './PresetPage'

vi.mock('@/data/useMetric', async (original) => ({ ...(await original<typeof import('@/data/useMetric')>()), useMetric: vi.fn() }))
vi.mock('@/data/streamHooks', () => ({ useStatus: vi.fn(), useLogs: vi.fn(), useWsEvents: vi.fn() }))

const NOW = Date.parse('2026-10-18T14:02:00Z')
const TIMES = Array.from({ length: 4 }, (_, i) => NOW - (3 - i) * 15_000)

/** A seeded series over the test window: value i * k + base at each bucket. */
const gen = (label: string, base: number, k = 1): Series => ({ label, color: '#000', points: TIMES.map((t, i) => ({ t, v: base + i * k })) })

type Answer = (q: MetricQuery) => Series[]
function answerMetrics(answer: Answer, state: Partial<MetricState> = {}) {
  vi.mocked(useMetric).mockImplementation((req) => {
    const queries = Array.isArray(req) ? req : [req]
    return { series: queries.flatMap(answer), times: TIMES, loading: false, refreshing: false, ...state }
  })
}

const services = [
  { name: 'backend', state: 'UP' as const, detail: null, uptimeMs: 187_200_000 },
  { name: 'mongodb', state: 'DOWN' as const, detail: 'timeout', uptimeMs: null },
]

function renderSpec(spec: PageSpec) {
  return render(
    <MemoryRouter>
      <LiveProvider>
        <PresetPage spec={spec} />
      </LiveProvider>
    </MemoryRouter>,
  )
}

const card = (name: string) => screen.getByText(name, { selector: 'div > div' }).closest('div.rounded-lg') as HTMLElement

describe('PresetPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: NOW })
    vi.mocked(useStatus).mockReturnValue({ data: { services, dev: false }, loading: false })
    vi.mocked(useLogs).mockReturnValue({
      data: [{ time: '2026-10-18T14:01:59.120Z', level: 'warn', service: 'bridge', message: 'backend unavailable, retrying', requestId: 'r7f3a' }],
      loading: false,
    })
    answerMetrics(() => [])
  })
  afterEach(() => vi.useRealTimers())

  it('presetPageRendersEveryWidgetKindFromASpec', () => {
    const spec: PageSpec = {
      crumb: 'Infrastructure',
      title: 'Sample',
      tabs: ['First', 'Second'],
      chips: ['broker redpanda'],
      note: 'What this page cannot show.',
      groups: [
        {
          title: 'kinds',
          mono: true,
          tag: 'one of each',
          right: 'X-Api-Key',
          tab: 'First',
          widgets: [
            { kind: 'bars', title: 'Stacked', sub: 'by result', span: 4, query: [{ metric: 'a', agg: 'increase' }] },
            { kind: 'lines', title: 'Lines', sub: (_, s) => `${s.length} series`, span: 4, query: [{ metric: 'b', agg: 'p95' }], format: (v) => `${v} u` },
            { kind: 'tile', title: 'Share', span: 2, query: [{ metric: 'a', agg: 'increase' }], value: (s) => ({ value: `${s.length} seen`, tone: 'warn' }) },
            { kind: 'tile', title: 'Clock', span: 2, query: [], value: (_, { now }) => ({ value: now === NOW ? 'now' : 'later', tone: 'ok', label: 'From the clock' }) },
            { kind: 'toplist', title: 'Ranked', span: 4, query: [{ metric: 'a', agg: 'increase' }], rows: (s) => s.map((x) => ({ name: x.label, value: 1 })), color: '#000', format: String },
            { kind: 'status', title: 'Backend', span: 3, query: { service: 'backend', show: 'state' } },
            { kind: 'status', title: 'Uptime', span: 3, query: { service: 'backend', show: 'uptime' } },
            { kind: 'status', title: 'Mongo health', span: 2, query: { service: 'mongodb', show: 'state' } },
          ],
        },
        {
          title: 'Streams',
          tab: 'Second',
          widgets: [
            { kind: 'stream', source: 'logs', title: 'Logs', span: 6, query: { service: 'bridge', limit: 5 }, rows: (lines) => lines.map((l) => ({ time: 'then', tag: l.level ?? '-', tone: 'warn', text: l.message ?? '' })) },
            { kind: 'stream', source: 'metrics', title: 'Derived', span: 6, query: [{ metric: 'a', agg: 'last' }], rows: (s) => s.map((x) => ({ time: 'src', tag: 'ok', tone: 'ok', text: x.label })) },
          ],
        },
      ],
    }
    answerMetrics((q) => [gen(`${q.metric} one`, 1), gen(`${q.metric} two`, 2)])
    renderSpec(spec)

    expect(screen.getByRole('heading', { level: 1, name: 'Sample' })).toBeInTheDocument()
    expect(screen.getByText('broker redpanda')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Time range' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'First' })).toHaveAttribute('aria-selected', 'true')
    const kinds = screen.getByRole('region', { name: 'kinds' })
    expect(within(kinds).getByText('kinds')).toHaveClass('font-mono')
    expect(within(kinds).getByText('one of each')).toBeInTheDocument()
    expect(within(kinds).getByText('X-Api-Key')).toBeInTheDocument()

    expect(screen.getByRole('img', { name: /^Stacked by result: a one 4, a two 5$/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /^Lines 2 series: b one 4 u, b two 5 u$/ })).toBeInTheDocument()
    expect(screen.getByText('2 seen')).toHaveClass('text-wn')
    expect(screen.getByText('now')).toBeInTheDocument()
    expect(screen.getByText('From the clock')).toBeInTheDocument()
    expect(within(card('Ranked')).getByText('a one')).toBeInTheDocument()
    expect(screen.getByText('UP')).toHaveClass('text-ok')
    expect(screen.getByText('2d 4h')).toBeInTheDocument()
    expect(screen.getByText('DOWN')).toHaveClass('text-er')
    expect(screen.getByText('Mongo health, timeout')).toBeInTheDocument()
    expect(within(card('Logs')).getByText('backend unavailable, retrying')).toBeInTheDocument()
    expect(useLogs).toHaveBeenCalledWith({ service: 'bridge', limit: 5 })
    expect(within(card('Derived')).getByText('a one')).toBeInTheDocument()
    expect(screen.getByText('What this page cannot show.')).toBeInTheDocument()
    // Widths come from the spec.
    expect(card('Stacked')).toHaveClass('col-span-4')
    expect(card('Logs')).toHaveClass('col-span-6')
  })

  it('tabsScrollToTheirGroup', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    renderSpec(INFRA_PAGES.backend)
    await user.click(screen.getByRole('tab', { name: 'Mongo pool' }))
    expect(screen.getByRole('tab', { name: 'Mongo pool' })).toHaveAttribute('aria-selected', 'true')
    expect(scroll).toHaveBeenCalledTimes(1)
    expect(scroll.mock.contexts[0]).toBe(screen.getByRole('region', { name: 'mongo connection pool' }).parentElement)
  })

  it('showsLoadingErrorAndEmptyPerWidgetNeverAZero', () => {
    const spec: PageSpec = {
      crumb: 'Infrastructure',
      title: 'States',
      tabs: ['All'],
      chips: [],
      groups: [
        {
          title: 'states',
          widgets: [
            { kind: 'bars', title: 'Bars', span: 4, query: [{ metric: 'a', agg: 'increase' }] },
            { kind: 'tile', title: 'Tile', span: 4, query: [{ metric: 'a', agg: 'increase' }], value: () => undefined },
            { kind: 'status', title: 'Gone', span: 4, query: { service: 'bridge', show: 'state' } },
            { kind: 'status', title: 'Mongo uptime', span: 4, query: { service: 'mongodb', show: 'uptime' } },
          ],
        },
      ],
    }
    answerMetrics(() => [], { loading: true })
    const { unmount } = renderSpec(spec)
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(2)
    unmount()

    answerMetrics(() => [], { error: new ApiError(400, 'QUERY_TOO_BROAD', 'Query too broad') })
    vi.mocked(useStatus).mockReturnValue({ data: { services: [], dev: false }, loading: false, error: new ApiError(503, 'X', 'Status unavailable') })
    const second = renderSpec(spec)
    expect(screen.getAllByText('Query too broad')).toHaveLength(2)
    expect(screen.getAllByText('Status unavailable')).toHaveLength(2)
    second.unmount()

    answerMetrics(() => [{ label: 'a', color: '#000', points: [] }])
    vi.mocked(useStatus).mockReturnValue({ data: { services, dev: false }, loading: false })
    renderSpec(spec)
    expect(screen.getAllByText('No data in this range')).toHaveLength(2)
    expect(within(card('Gone')).getByText('Not reported by the status check')).toBeInTheDocument()
    expect(within(card('Mongo uptime')).getByText('Not reported by the status check')).toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  it('statusTilesWaitForTheirFirstFrame', () => {
    vi.mocked(useStatus).mockReturnValue({ data: { services: [], dev: false }, loading: true })
    renderSpec(INFRA_PAGES.backend)
    const health = screen.getByRole('region', { name: 'health' })
    expect(within(health).getAllByRole('status', { name: 'Loading' })).toHaveLength(4)
  })

  it('edgeShowsTheEmptyStateWithoutSamples', () => {
    renderSpec(INFRA_PAGES.edge)
    const empty = screen.getAllByText(/^Only exists in production/)
    // Every widget of the page: four tiles and four charts.
    expect(empty).toHaveLength(8)
    expect(screen.queryByText('No data in this range')).not.toBeInTheDocument()
    expect(screen.queryByText(/%$/)).not.toBeInTheDocument()
  })

  it('backendActiveRequestsAndUptimePanelsHaveSources', () => {
    answerMetrics((q) => (q.metric === 'http.server.active' ? [gen('active requests', 3)] : []))
    renderSpec(INFRA_PAGES.backend)
    expect(screen.getByRole('img', { name: 'Active requests in flight: active requests 6' })).toBeInTheDocument()
    expect(within(card('Uptime')).getByText('2d 4h')).toBeInTheDocument()
    expect(useMetric).toHaveBeenCalledWith([{ metric: 'http.server.active', agg: 'max', filters: {}, groupBy: [], label: 'active requests' }])
    expect(screen.getByText(/no rejected-connections count/)).toBeInTheDocument()
  })

  it('cronPageShowsSuggestionsPerRun', () => {
    answerMetrics((q) => {
      const grouped = q.groupBy?.includes('result')
      if (q.filters?.field === 'suggestions') return [gen(grouped ? 'suggestions ok' : 'suggestions', 10, 2)]
      return [gen(q.label ? `${q.label} ok` : 'ok', 1400)]
    })
    renderSpec(INFRA_PAGES.cron)
    expect(screen.getByRole('img', { name: 'Suggestions rows applied per run: suggestions 16' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'recent runs' })).getAllByText(/^\d+ suggestions, 1\.4 s$/)).toHaveLength(4)
    expect(screen.getByText(/^\d\d:\d\d (today|tomorrow)$/)).toBeInTheDocument()
  })

  it('telemetryFreshnessFlagsAStaleSource', () => {
    answerMetrics((q) =>
      q.metric === 'jvm.heap.used'
        ? [gen('jvm.heap.used', 1)]
        : q.metric === 'bridge.consumer.lag'
          ? [{ label: 'bridge.consumer.lag', color: '#000', points: [{ t: NOW - 120_000, v: 0 }] }]
          : [],
    )
    renderSpec(INFRA_PAGES.telemetry)
    const list = card('Latest sample per metric')
    expect(within(list).getByText('2 min')).toHaveClass('text-wn')
    expect(within(list).getByText('0 s')).toHaveClass('text-ok')
    // The UI clock ages the samples without any new data.
    act(() => void vi.advanceTimersByTime(70_000))
    expect(within(list).getByText('1 min')).toHaveClass('text-wn')
  })

  it('mongoPageSubscribesOnlyForGroupsNearTheView', () => {
    const observers: { cb: IntersectionObserverCallback; el?: Element }[] = []
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        entry: { cb: IntersectionObserverCallback; el?: Element }
        constructor(cb: IntersectionObserverCallback) {
          this.entry = { cb }
          observers.push(this.entry)
        }
        observe(el: Element) {
          this.entry.el = el
        }
        disconnect() {}
      },
    )
    renderSpec(INFRA_PAGES.mongo)
    expect(observers).toHaveLength(10)
    expect(useMetric).not.toHaveBeenCalled()
    const [overview, orders] = observers
    act(() => {
      for (const o of [overview, orders]) o.cb([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
    })
    const asked = vi.mocked(useMetric).mock.calls.flatMap(([req]) => (Array.isArray(req) ? req : [req]))
    const collections = new Set(asked.flatMap((q) => (q.filters?.collection ? [q.filters.collection] : [])))
    expect([...collections]).toEqual(['orders'])
    expect(screen.getByRole('region', { name: 'orders' })).toBeInTheDocument()
  })
})
