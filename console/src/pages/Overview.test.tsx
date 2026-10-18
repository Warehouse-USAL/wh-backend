import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { LogLine } from '@/api/logs'
import type { MetricQuery } from '@/api/metrics'
import catalog from '@/api/__fixtures__/metrics-catalog.json'
import { LiveProvider } from '@/data/live'
import { type ServiceStatus, useLogs, useStatus } from '@/data/streamHooks'
import { type MetricState, useMetric } from '@/data/useMetric'
import type { Series } from '@/widgets/scale'
import Overview from './Overview'

vi.mock('@/data/streamHooks', () => ({ useStatus: vi.fn(), useLogs: vi.fn(), useWsEvents: vi.fn() }))
vi.mock('@/data/useMetric', async (importOriginal) => ({ ...(await importOriginal<object>()), useMetric: vi.fn() }))

const T0 = Date.parse('2026-10-18T14:00:00Z')
const series = (label: string, values: number[]): Series => ({ label, color: '#000', points: values.map((v, i) => ({ t: T0 + i * 15_000, v })) })
const state = (s: Series[], extra: Partial<MetricState> = {}): MetricState => ({ series: s, times: [], loading: false, refreshing: false, ...extra })

const svc = (name: string, st: ServiceStatus['state'] = 'UP', detail: string | null = null): ServiceStatus => ({ name, state: st, detail, uptimeMs: null })
const SERVICES = ['backend', 'bridge', 'mongodb', 'redpanda', 'minio', 'victoriametrics', 'victorialogs', 'otel-collector'].map((n) => svc(n))

const line = (time: string, level: string, message: string, requestId: string | null = null): LogLine => ({ time, level, service: 'backend', message, requestId })

type Answer = (q: MetricQuery) => MetricState
function stubMetrics(answer: Answer = () => state([])) {
  vi.mocked(useMetric).mockImplementation((req) => answer((Array.isArray(req) ? req[0] : req) as MetricQuery))
}

function stubStatus(services: ServiceStatus[], loading = false) {
  vi.mocked(useStatus).mockReturnValue({ data: { services, dev: false }, loading })
}

function Where() {
  const { pathname, search } = useLocation()
  return <p>at {pathname + search}</p>
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/overview']}>
      <LiveProvider>
        <Routes>
          <Route path="/overview" element={<Overview />} />
          <Route path="/infra/logs" element={<Where />} />
        </Routes>
      </LiveProvider>
    </MemoryRouter>,
  )

const group = (name: string) => screen.getByRole('region', { name: new RegExp(name) })

describe('Overview', () => {
  beforeEach(() => {
    stubStatus(SERVICES)
    stubMetrics()
    vi.mocked(useLogs).mockReturnValue({ data: [], loading: false })
  })

  it('overviewShowsOneTilePerService', () => {
    renderPage()

    const tiles = within(group('services')).getAllByText(/^(UP|DOWN|UNKNOWN|OK|FAILED|NO RUN)$/)
    // Eight services from the status payload plus the restock cron, whose state is its last run.
    expect(tiles).toHaveLength(SERVICES.length + 1)
    for (const s of SERVICES) expect(within(group('services')).getByText(new RegExp(`^${s.name}`))).toBeInTheDocument()
    expect(within(group('services')).getByText(/^restock-cron/)).toBeInTheDocument()
  })

  it('downServiceTileIsRed', () => {
    stubStatus([svc('backend'), svc('bridge', 'DOWN', 'unreachable'), svc('victorialogs', 'UNKNOWN')])
    renderPage()

    expect(screen.getByText('DOWN')).toHaveClass('text-er')
    expect(screen.getByText('bridge, unreachable')).toBeInTheDocument()
    expect(screen.getAllByText('UP')[0]).toHaveClass('text-ok')
    expect(screen.getByText('UNKNOWN')).toHaveClass('text-wn')
  })

  it('detailLinesComeFromTheLatestMetricOfEachService', () => {
    stubMetrics((q) => {
      const v = { 'http.server.duration': [10, 20], 'bridge.consumer.lag': [3], 'mongo.command.duration': [6], 'kafka.messages': [2],
        'minio.objects': [2310], 'vm.series.active': [1240], 'collector.received': [100] }[q.metric]
      return state(v ? [series(q.metric, v)] : [])
    })
    renderPage()

    for (const text of ['backend, 1.2k req/min', 'bridge, lag 3', 'mongodb, p95 6 ms', 'redpanda, 120 msg/min', 'minio, 2.3k objects',
      'victoriametrics, 1.2k series', 'otel-collector, 6k points/min', 'victorialogs']) {
      expect(screen.getByText(text)).toBeInTheDocument()
    }
  })

  it('omitsTheDetailWhenTheMetricHasNoDataOrFails', () => {
    stubMetrics((q) => (q.metric === 'bridge.consumer.lag' ? state([], { error: new ApiError(500, 'X', 'boom') }) : state([])))
    renderPage()

    expect(screen.getByText('backend')).toBeInTheDocument()
    expect(screen.getByText('bridge')).toBeInTheDocument()
  })

  it('everyMetricItAsksForExistsInTheCatalogueWithItsAggregationAndTags', () => {
    const asked: MetricQuery[] = []
    stubMetrics((q) => (asked.push(q), state([])))
    renderPage()

    expect(new Set(asked.map((q) => q.metric))).toEqual(
      new Set(['http.server.duration', 'bridge.consumer.lag', 'mongo.command.duration', 'kafka.messages', 'minio.objects', 'vm.series.active', 'collector.received', 'job.run']),
    )
    for (const q of asked) {
      const m = catalog.metrics.find((x) => x.name === q.metric)
      expect(m?.permitted_aggregations).toContain(q.agg)
      for (const k of [...Object.keys(q.filters ?? {}), ...(q.groupBy ?? [])]) expect(m?.dimensions).toContain(k)
    }
  })

  it('restockCronTileShowsTheLastRunAndItsResult', () => {
    const at = new Date(T0 + 15_000)
    const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`
    stubMetrics((q) => (q.metric === 'job.run' ? state([series('ok', [1400]), series('error', [0, 0])]) : state([])))
    const { unmount } = renderPage()
    expect(screen.getByText('FAILED')).toHaveClass('text-er')
    expect(screen.getByText(`restock-cron, last run ${hm}`)).toBeInTheDocument()
    unmount()

    stubMetrics((q) => (q.metric === 'job.run' ? state([series('ok', [0, 1400])]) : state([])))
    renderPage()
    expect(screen.getByText('OK')).toHaveClass('text-ok')
  })

  it('restockCronTileSaysWhenNoRunFellInTheRange', () => {
    renderPage()

    expect(screen.getByText('NO RUN')).toBeInTheDocument()
    expect(screen.getByText('restock-cron, no run in the past 15m')).toBeInTheDocument()
  })

  it('showsLoadingWhileTheStatusHasNotArrived', () => {
    stubStatus([], true)
    stubMetrics((q) => (q.metric === 'job.run' ? state([], { loading: true }) : state([])))
    renderPage()

    expect(within(group('services')).getAllByRole('status', { name: 'Loading' }).length).toBeGreaterThan(0)
  })

  it('rightNowShowsAllEndpointsTogether', () => {
    const asked: MetricQuery[] = []
    stubMetrics((q) => {
      asked.push(q)
      return q.metric === 'http.server.duration' && q.agg === 'increase' ? state([series('2xx', [90, 90]), series('5xx', [10, 10])]) : state([])
    })
    renderPage()

    const now = group('right now')
    for (const title of ['Hits', 'Latency', 'Error rate', 'Success rate']) expect(within(now).getByText(title)).toBeInTheDocument()
    expect(within(now).getByText('90%')).toBeInTheDocument()
    const http = asked.filter((q) => q.metric === 'http.server.duration' && q.agg !== 'rate')
    expect(http.length).toBeGreaterThan(0)
    for (const q of http) expect(q.filters).toEqual({})
  })

  it('rightNowShowsErrorsAndEmptyStates', () => {
    stubMetrics((q) => (q.agg === 'p50' ? state([], { error: new ApiError(503, 'VM_DOWN', 'Metrics store unavailable') }) : state([])))
    renderPage()

    const now = group('right now')
    expect(within(now).getByText('Metrics store unavailable')).toBeInTheDocument()
    expect(within(now).getAllByText('No data in this range').length).toBeGreaterThan(0)
  })

  it('recentProblemsAreErrorAndWarnLinesNewestFirstAndOpenTheRequest', async () => {
    vi.mocked(useLogs).mockImplementation(({ level }) => ({
      data:
        level === 'error'
          ? [line('2026-10-18T14:02:12.640Z', 'error', 'PATCH /internal/orders returned 500', 'req7f3c')]
          : [line('2026-10-18T14:02:13.000Z', 'warn', 'bridge retry', null), line('2026-10-18T14:01:00.000Z', 'warn', 'slow mongo', 'r2')],
      loading: false,
    }))
    renderPage()

    const rows = within(group('recent problems')).getAllByText(/returned 500|bridge retry|slow mongo/)
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('bridge retry'),
      expect.stringContaining('returned 500'),
      expect.stringContaining('slow mongo'),
    ])
    expect(vi.mocked(useLogs)).toHaveBeenCalledWith(expect.objectContaining({ level: 'error' }))
    expect(vi.mocked(useLogs)).toHaveBeenCalledWith(expect.objectContaining({ level: 'warn' }))

    await userEvent.setup().click(screen.getByRole('link', { name: 'req=req7f3c' }))
    expect(screen.getByText('at /infra/logs?req=req7f3c')).toBeInTheDocument()
  })

  it('recentProblemsShowLoadingErrorsAndLinesWithMissingFields', () => {
    vi.mocked(useLogs).mockReturnValue({ data: [], loading: true })
    const { unmount } = renderPage()
    expect(within(group('recent problems')).getByRole('status', { name: 'Loading' })).toBeInTheDocument()
    unmount()

    vi.mocked(useLogs).mockReturnValue({ data: [], loading: false, error: new ApiError(503, 'LOGS_DOWN', 'Log store unavailable') })
    const second = renderPage()
    expect(within(group('recent problems')).getByText('Log store unavailable')).toHaveClass('text-er')
    second.unmount()

    const bare: LogLine = { time: null, level: null, service: null, message: 'no fields', requestId: null }
    vi.mocked(useLogs).mockImplementation(({ level }) => ({ data: level === 'warn' ? [bare, line('2026-10-18T14:00:00Z', 'warn', 'timed')] : [], loading: false }))
    renderPage()
    const rows = within(group('recent problems')).getAllByText(/no fields|timed/)
    expect(rows.map((r) => r.textContent)).toEqual(['backend: timed', 'no fields'])
    expect(within(group('recent problems')).getByText('-', { selector: 'span.rounded-full' })).toHaveClass('text-wn')
  })

  it('recentProblemsEmptyState', () => {
    renderPage()

    expect(within(group('recent problems')).getByText('No errors or warnings')).toBeInTheDocument()
  })

  it('tabsScrollToTheirSection', async () => {
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    renderPage()

    await userEvent.setup().click(screen.getByRole('tab', { name: 'Problems' }))
    expect(screen.getByRole('tab', { name: 'Problems' })).toHaveAttribute('aria-selected', 'true')
    expect(scroll).toHaveBeenCalledOnce()
  })
})
