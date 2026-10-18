import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { clearMetricAnswers } from '@/api/metrics'
import { LiveProvider } from '@/data/live'
import { windowFor } from '@/data/useMetric'
import { type OpenApiDoc, loadSpec } from '@/domains/template'
import { jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'
import MonitoringPage from './MonitoringPage'
import type { Domain } from './routes'

vi.mock('@/domains/template', () => ({ loadSpec: vi.fn() }))

const doc = spec as unknown as OpenApiDoc

type Body = { agg: string; filters: Record<string, string>; group_by: string[] }
type Answer = (body: Body) => Response | { labels: Record<string, string>; points: [number, number][] }[]

/** Answers every metrics query through `answer`; returns the bodies it was asked. */
function stubMetrics(answer: Answer = () => []) {
  const bodies: Body[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as Body
      bodies.push(body)
      const out = answer(body)
      return out instanceof Response ? out : jsonResponse(200, { metric: 'http.server.duration', unit: 'ms', step: '15s', series: out })
    }),
  )
  return bodies
}

const isOverview = (b: Body) => b.group_by.includes('route')
const routeBodies = (bodies: Body[]) => bodies.filter((b) => !isOverview(b))

function renderPage(domain: Domain, extra?: ReactNode) {
  return render(
    <MemoryRouter>
      <LiveProvider>
        <Routes>
          <Route path="/" element={<MonitoringPage domain={domain} extra={extra} />} />
          <Route path="/infra/mongodb" element={<p>MongoDB page</p>} />
        </Routes>
      </LiveProvider>
    </MemoryRouter>,
  )
}

const groups = () => screen.getAllByRole('region').map((r) => within(r).getAllByRole('heading')[0].textContent)

describe('MonitoringPage', () => {
  beforeEach(() => {
    clearMetricAnswers()
    setSession({ token: 'tok', email: 'root@x.io' })
    vi.mocked(loadSpec).mockResolvedValue(doc)
  })

  it('groupsAreGeneratedFromTheRoutesNotHandWritten', async () => {
    stubMetrics()
    const { unmount } = renderPage('Products')
    await screen.findByRole('region', { name: 'GET /products' })
    expect(groups()).toEqual([
      'Overview',
      'GET /products',
      'POST /products',
      'GET /products/categories',
      'GET /products/{id}',
      'PATCH /products/{id}',
      'DELETE /products/{id}',
      'GET /products/{id}/location',
    ])
    unmount()

    const paths = { ...doc.paths, '/products/{id}/fake': { get: { tags: ['Products'], summary: 'A fake route' } } }
    vi.mocked(loadSpec).mockResolvedValue({ ...doc, paths })
    renderPage('Products')
    const fake = await screen.findByRole('region', { name: 'GET /products/{id}/fake' })
    expect(fake).toHaveTextContent('A fake route')
    expect(screen.getByRole('heading', { name: /^Products\s*8 endpoints$/ })).toBeInTheDocument()
  })

  it('overviewRanksByRequestCount', async () => {
    stubMetrics((b) =>
      isOverview(b) && b.agg === 'increase' && !b.filters.status_class
        ? [
            { labels: { method: 'GET', route: '/orders' }, points: [[1, 30], [2, 15]] },
            { labels: { method: 'GET', route: 'UNMATCHED' }, points: [[1, 900]] },
            { labels: { method: 'GET', route: '/products' }, points: [[1, 600]] },
            { labels: { method: 'POST', route: '/orders' }, points: [[1, 150]] },
            { labels: { method: 'GET', route: '/orders/{id}' }, points: [[1, 0]] },
          ]
        : [],
    )
    renderPage('Orders')
    const list = await screen.findByText('Requests')
    const card = list.closest('div.rounded-lg') as HTMLElement
    await waitFor(() => expect(within(card).getAllByText(/^(GET|POST|PATCH)/)).toHaveLength(3))
    // 15 minutes of range: 150 requests is 10 per minute. UNMATCHED and other pages' routes stay out.
    expect(within(card).getAllByText(/^(GET|POST|PATCH)/).map((e) => e.textContent)).toEqual(['POST /orders', 'GET /orders', 'GET /orders/{id}'])
    expect(card).toHaveTextContent('per min')
    expect(card).toHaveTextContent('POST /orders10')
    expect(card).toHaveTextContent('GET /orders3')
  })

  it('ranksLatencyByThePeakP95AndErrorsByTheirCount', async () => {
    stubMetrics((b) => {
      if (!isOverview(b)) return []
      if (b.agg === 'p95') {
        return [
          { labels: { method: 'GET', route: '/orders' }, points: [[1, 20], [2, 1500]] },
          { labels: { method: 'POST', route: '/orders' }, points: [[1, 80]] },
        ]
      }
      return b.filters.status_class === '5xx' ? [{ labels: { method: 'POST', route: '/orders' }, points: [[1, 2], [2, 3]] }] : []
    })
    renderPage('Orders')
    const overview = await screen.findByRole('region', { name: 'Overview' })
    const latency = (await within(overview).findByText('Latency')).closest('div.rounded-lg') as HTMLElement
    await waitFor(() => expect(latency).toHaveTextContent('GET /orders1.5 s'))
    expect(latency).toHaveTextContent('p95, slowest first')
    expect(latency).toHaveTextContent('POST /orders80 ms')
    const errors = screen.getByText('Errors').closest('div.rounded-lg') as HTMLElement
    expect(errors).toHaveTextContent('5xx, last 15m')
    await waitFor(() => expect(errors).toHaveTextContent('POST /orders5'))
  })

  it('groupShowsHitsLatencyErrorRateAndSuccessForItsRouteAndMethod', async () => {
    const { times } = windowFor('15m', Date.now())
    const at = times.at(-1) as number
    const bodies = stubMetrics((b) => {
      if (b.filters.route !== '/auth/login') return []
      if (b.agg === 'increase') {
        return [
          { labels: { status_class: '2xx' }, points: [[at / 1000, 95]] },
          { labels: { status_class: '4xx' }, points: [[at / 1000, 3]] },
          { labels: { status_class: '5xx' }, points: [[at / 1000, 2]] },
        ]
      }
      return [{ labels: {}, points: [[at / 1000, b.agg === 'p50' ? 40 : 90]] }]
    })
    renderPage('Users and auth')
    const login = await screen.findByRole('region', { name: 'POST /auth/login' })
    expect(within(login).getByText('Login')).toBeInTheDocument()
    expect(within(login).getByText('public')).toBeInTheDocument()
    await waitFor(() => expect(within(login).getByText('98%')).toBeInTheDocument())
    expect(within(login).getByText('Success rate')).toBeInTheDocument()
    expect(within(login).getByRole('img', { name: 'Hits by status class: 2xx 95, 4xx 3, 5xx 2' })).toBeInTheDocument()
    expect(within(login).getByRole('img', { name: 'Latency p50 / p95 / p99: p50 40 ms, p95 90 ms, p99 90 ms' })).toBeInTheDocument()
    expect(within(login).getByRole('img', { name: 'Error rate: 5xx % 2%' })).toBeInTheDocument()
    expect(routeBodies(bodies).filter((b) => b.filters.route === '/auth/login').every((b) => b.filters.method === 'POST')).toBe(true)
    // Another route without traffic: honest empty states, no fake 100%.
    const users = screen.getByRole('region', { name: 'GET /users' })
    await waitFor(() => expect(within(users).getAllByText('No data in this range')).toHaveLength(4))
  })

  it('showsAWidgetErrorWithoutBreakingThePage', async () => {
    stubMetrics((b) =>
      b.agg === 'p50' && b.filters.route === '/vehicles' && b.filters.method === 'GET'
        ? jsonResponse(400, { error: { code: 'UNKNOWN_METRIC', message: 'no such metric' } })
        : [],
    )
    renderPage('Vehicles')
    const region = await screen.findByRole('region', { name: 'GET /vehicles' })
    expect(await within(region).findByText('no such metric')).toBeInTheDocument()
    await waitFor(() => expect(within(region).getAllByText('No data in this range')).toHaveLength(3))
    expect(screen.getByRole('region', { name: 'POST /vehicles' })).not.toHaveTextContent('no such metric')
  })

  it('showsSkeletonsUntilTheFirstAnswer', async () => {
    // Never answers, but lets go when aborted so the metrics client's request slots free up.
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>((_input, init) => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))),
    )
    renderPage('Files')
    const region = await screen.findByRole('region', { name: 'GET /api/v1/files' })
    expect(within(region).getAllByRole('status', { name: 'Loading' })).toHaveLength(4)
  })

  it('rendersGroupBodiesOnlyNearTheViewport', async () => {
    const observers: { cb: IntersectionObserverCallback; els: Element[]; off: boolean }[] = []
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        entry: { cb: IntersectionObserverCallback; els: Element[]; off: boolean }
        constructor(cb: IntersectionObserverCallback) {
          this.entry = { cb, els: [], off: false }
          observers.push(this.entry)
        }
        observe(el: Element) {
          this.entry.els.push(el)
        }
        disconnect() {
          this.entry.off = true
        }
      },
    )
    const bodies = stubMetrics()
    renderPage('Infra callbacks')
    await screen.findByRole('region', { name: 'POST /infra/orders/{orderId}/status' })
    await waitFor(() => expect(bodies.filter(isOverview)).toHaveLength(3))
    expect(routeBodies(bodies)).toHaveLength(0)

    const target = observers.find((o) => o.els[0]?.textContent?.includes('/infra/orders/{orderId}/status')) as (typeof observers)[0]
    const show = (isIntersecting: boolean) =>
      act(() => target.cb([{ isIntersecting, target: target.els[0] } as IntersectionObserverEntry], {} as IntersectionObserver))
    show(true)
    await waitFor(() => expect(routeBodies(bodies).length).toBeGreaterThan(0))
    expect(new Set(routeBodies(bodies).map((b) => b.filters.route))).toEqual(new Set(['/infra/orders/{orderId}/status']))

    // Scrolled away: the widgets unmount (polling stops) and their height is kept.
    show(false)
    const region = screen.getByRole('region', { name: 'POST /infra/orders/{orderId}/status' })
    expect(within(region).queryByText('Hits')).toBeNull()
    expect(region.querySelector('[aria-hidden="true"].col-span-12')).not.toBeNull()
  })

  it('showsAnHonestStateWhenTheApiDescriptionIsUnavailable', async () => {
    stubMetrics()
    vi.mocked(loadSpec).mockResolvedValueOnce(null)
    renderPage('Restock')
    expect(await screen.findByText('API description unavailable')).toBeInTheDocument()
    expect(screen.queryByRole('region')).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('region', { name: 'GET /restock/orders' })).toBeInTheDocument()
    expect(screen.queryByText('API description unavailable')).toBeNull()
  })

  it('tabsJumpToTheirSectionOrOpenTheRelatedPage', async () => {
    stubMetrics()
    const scrolled: string[] = []
    Element.prototype.scrollIntoView = vi.fn(function (this: Element) {
      scrolled.push(this.textContent ?? '')
    })
    renderPage('Warehouse')
    await screen.findByRole('region', { name: 'GET /warehouse/zones' })
    const user = userEvent.setup()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Zones', 'Lines', 'Positions'])
    await user.click(screen.getByRole('tab', { name: 'Positions' }))
    expect(scrolled.at(-1)).toMatch(/^Positions/)
    expect(screen.getByRole('tab', { name: 'Positions' })).toHaveAttribute('aria-selected', 'true')
  })

  it('linkTabsOpenTheirPageAndTheExtraTabNeedsAnExtra', async () => {
    stubMetrics()
    Element.prototype.scrollIntoView = vi.fn()
    const { unmount } = renderPage('Orders')
    await screen.findByRole('region', { name: 'GET /orders' })
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Endpoints', 'Collection orders'])
    unmount()

    renderPage('Orders', <p>trail here</p>)
    await screen.findByRole('region', { name: 'GET /orders' })
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Endpoints', 'Event trail', 'Collection orders'])
    const trail = screen.getByText('trail here')
    const lastGroup = screen.getAllByRole('region').at(-1) as HTMLElement
    expect(lastGroup.compareDocumentPosition(trail) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: 'Event trail' }))
    await user.click(screen.getByRole('tab', { name: 'Endpoints' }))
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2)
    await user.click(screen.getByRole('tab', { name: 'Collection orders' }))
    expect(screen.getByText('MongoDB page')).toBeInTheDocument()
  })
})
