import { act, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import fixture from '@/api/__fixtures__/logs-query.json'
import App from '@/App'
import { setSession } from '@/api/client'
import { clearMetricAnswers } from '@/api/metrics'
import { type OpenApiDoc, loadSpec } from '@/domains/template'
import { jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'

vi.mock('@/domains/template', () => ({ loadSpec: vi.fn() }))

type Body = { request_id?: string; text?: string; service?: string }

/** Logs answer by filter (the latest-order probe, then one request's trail); metrics answer nothing. */
function stubApi(answer: (b: Body) => Response) {
  const bodies: Body[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      if (String(input).endsWith('/metrics/query')) return jsonResponse(200, { metric: 'm', unit: '', step: '15s', series: [] })
      const body = JSON.parse(String(init?.body)) as Body
      bodies.push(body)
      return answer(body)
    }),
  )
  return bodies
}

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )

describe('RequestTrail', () => {
  beforeEach(() => {
    clearMetricAnswers()
    setSession({ token: 'tok', email: 'root@x.io' })
    vi.mocked(loadSpec).mockResolvedValue(spec as unknown as OpenApiDoc)
  })

  it('ordersPageHasTheEventTrail', async () => {
    const bodies = stubApi((b) => jsonResponse(200, b.request_id ? { lines: fixture.lines.slice(0, 2), total: 2 } : { lines: [fixture.lines[0]], total: 1 }))
    renderAt('/monitoring/orders')

    const trail = await screen.findByRole('region', { name: 'Event trail' })
    const rows = await within(trail).findByText('order published to infra')
    expect(bodies[0]).toMatchObject({ service: 'bridge', text: 'order published to infra', limit: 1 })
    expect(bodies[1]).toMatchObject({ request_id: 't23order1' })
    // Oldest first, tagged by service.
    expect(within(trail).getAllByText('bridge')).toHaveLength(2)
    expect(trail).toHaveTextContent(/rabbitmq publish failed, retrying.*order published to infra/)
    expect(rows).toBeInTheDocument()
    expect(within(trail).getByRole('link', { name: 'Open in Logs' })).toHaveAttribute('href', '/infra/logs?req=t23order1')
  })

  it('tagsBackendLinesByTheComponentThatWroteThem', async () => {
    const line = (service: string, level: string, message: string) => ({ time: '2026-10-18T14:02:11.204Z', level, service, message, request_id: 'req-1' })
    const trailLines = [
      line('bridge', 'info', 'order published to infra'),
      line('backend', 'info', 'callback order o-1 status -> IN_PROGRESS'),
      line('backend', 'warn', 'mongo orders insert slow 130ms'),
      line('backend', 'info', 'kafka publish orders ok 4ms'),
      line('backend', 'info', 'http POST /orders 201 12ms'),
      line('backend', 'info', 'Started WhBackendApplication'),
    ]
    stubApi((b) => jsonResponse(200, b.request_id ? { lines: trailLines, total: 6 } : { lines: [trailLines[0]], total: 1 }))
    renderAt('/monitoring/orders')

    const trail = await screen.findByRole('region', { name: 'Event trail' })
    await within(trail).findByText('POST /orders 201 12ms')
    // Oldest first: the component word becomes the tag and leaves the text.
    expect(trail).toHaveTextContent(
      /backendStarted WhBackendApplication.*httpPOST \/orders 201 12ms.*kafkapublish orders ok 4ms.*mongoorders insert slow 130ms.*callbackorder o-1 status -> IN_PROGRESS.*bridgeorder published to infra/,
    )
  })

  it('refreshesOnEachTickKeepingTheTrailAndAbortingASupersededRequest', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    stubApi((b) => jsonResponse(200, b.request_id ? { lines: fixture.lines.slice(0, 2), total: 2 } : { lines: [fixture.lines[0]], total: 1 }))
    renderAt('/monitoring/orders')
    const trail = await screen.findByRole('region', { name: 'Event trail' })
    await within(trail).findByText('order published to infra')

    const signals: AbortSignal[] = []
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      if (String(input).endsWith('/metrics/query')) return jsonResponse(200, { metric: 'm', unit: '', step: '15s', series: [] })
      signals.push(init?.signal as AbortSignal)
      return new Promise<Response>(() => undefined)
    })
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(signals).toHaveLength(1)
    expect(signals[0].aborted).toBe(false)
    expect(within(trail).getByText('order published to infra')).toBeInTheDocument()

    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(signals[0].aborted).toBe(true)
    expect(within(trail).getByText('order published to infra')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('otherDomainPagesHaveNoTrail', async () => {
    const bodies = stubApi(() => jsonResponse(200, fixture))
    renderAt('/monitoring/products')
    await screen.findByRole('region', { name: 'GET /products' })
    expect(screen.queryByRole('region', { name: 'Event trail' })).toBeNull()
    expect(bodies).toHaveLength(0)
  })

  it('saysSoWhenNoOrderRequestIsLogged', async () => {
    stubApi(() => jsonResponse(200, { lines: [], total: 0 }))
    renderAt('/monitoring/orders')
    expect(await screen.findByText('No order request in the last 24 hours')).toBeInTheDocument()
  })

  it('showsTheErrorWhenTheStoreIsUnavailable', async () => {
    stubApi(() => jsonResponse(502, { error: { code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' } }))
    renderAt('/monitoring/orders')
    expect(await screen.findByText('LOG_STORE_UNAVAILABLE: The log store is unavailable')).toBeInTheDocument()
  })
})
