import { act, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import fixture from '@/api/__fixtures__/logs-query.json'
import App from '@/App'
import { setSession } from '@/api/client'
import type { WireSubscription } from '@/api/stream'
import { type OpenApiDoc, loadSpec } from '@/domains/template'
import { type Answer, fakeStream } from '@/test/stream'
import spec from './__fixtures__/api-docs.json'

vi.mock('@/domains/template', () => ({ loadSpec: vi.fn() }))

const EMPTY_METRIC = { metric: 'm', unit: '', step: '15s', series: [], window: { from: '2026-10-18T11:45:15Z', to: '2026-10-18T12:00:00Z', step: '15s' } }
const isTrail = (s: WireSubscription) => s.kind === 'logs' && s.request_id !== undefined
const isLatest = (s: WireSubscription) => s.kind === 'logs' && s.text === 'order published to infra'

/** A push backend: logs subscriptions answer through `answer`, metrics answer an empty frame. */
function stubStream(answer: (s: WireSubscription) => Answer) {
  return fakeStream({ answer: (s) => (s.kind === 'metric' ? { data: EMPTY_METRIC } : s.kind === 'logs' ? answer(s) : undefined) })
}

const logsSubs = (stream: ReturnType<typeof fakeStream>) => stream.opens.flatMap((c) => c.subscriptions).filter((s) => s.kind === 'logs')

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )

describe('RequestTrail', () => {
  beforeEach(() => {
    setSession({ token: 'tok', email: 'root@x.io' })
    vi.mocked(loadSpec).mockResolvedValue(spec as unknown as OpenApiDoc)
  })
  afterEach(() => vi.useRealTimers())

  it('ordersPageHasTheEventTrail', async () => {
    const stream = stubStream((s) => ({ data: isTrail(s) ? { lines: fixture.lines.slice(0, 2), total: 2 } : { lines: [fixture.lines[0]], total: 1 } }))
    renderAt('/monitoring/orders')

    const trail = await screen.findByRole('region', { name: 'Event trail' })
    const rows = await within(trail).findByText('order published to infra', {}, { timeout: 3000 })
    expect(logsSubs(stream).find(isLatest)).toMatchObject({ service: 'bridge', text: 'order published to infra', limit: 1, range: '24h' })
    expect(logsSubs(stream).find(isTrail)).toMatchObject({ request_id: 't23order1', limit: 500, range: '24h' })
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
    stubStream((s) => ({ data: isTrail(s) ? { lines: trailLines, total: 6 } : { lines: [trailLines[0]], total: 1 } }))
    renderAt('/monitoring/orders')

    const trail = await screen.findByRole('region', { name: 'Event trail' })
    await within(trail).findByText('POST /orders 201 12ms', {}, { timeout: 3000 })
    // Oldest first: the component word becomes the tag and leaves the text.
    expect(trail).toHaveTextContent(
      /backendStarted WhBackendApplication.*httpPOST \/orders 201 12ms.*kafkapublish orders ok 4ms.*mongoorders insert slow 130ms.*callbackorder o-1 status -> IN_PROGRESS.*bridgeorder published to infra/,
    )
  })

  it('aLineLoggedLaterJoinsTheTrailAndANewerOrderMovesItWithoutPolling', async () => {
    const stream = stubStream((s) => ({ data: isTrail(s) ? { lines: [fixture.lines[0]], total: 1 } : { lines: [fixture.lines[0]], total: 1 } }))
    renderAt('/monitoring/orders')
    const trail = await screen.findByRole('region', { name: 'Event trail' })
    await within(trail).findByText('order published to infra', {}, { timeout: 3000 })
    const opened = stream.fetch.mock.calls.length

    const later = { time: '2026-10-18T21:23:30Z', level: 'info', service: 'backend', message: 'callback order o-1 status -> DONE', request_id: 't23order1' }
    act(() => stream.last().send(isTrail, { lines: [later], total: 1 }))
    expect(await within(trail).findByText('order o-1 status -> DONE')).toBeInTheDocument()
    expect(trail).toHaveTextContent(/order published to infra.*order o-1 status -> DONE/)
    expect(stream.fetch).toHaveBeenCalledTimes(opened)

    // A newer order: the trail follows its request id.
    act(() => stream.last().send(isLatest, { lines: [{ ...fixture.lines[0], request_id: 'next-order' }], total: 1 }))
    expect(await within(trail).findByRole('link', { name: 'Open in Logs' })).toHaveAttribute('href', '/infra/logs?req=next-order')
    await vi.waitFor(() => expect(logsSubs(stream).some((s) => s.request_id === 'next-order')).toBe(true))
  })

  it('otherDomainPagesHaveNoTrail', async () => {
    const stream = stubStream(() => ({ data: fixture }))
    renderAt('/monitoring/products')
    await screen.findByRole('region', { name: 'GET /products' })
    await vi.waitFor(() => expect(stream.opens.length).toBeGreaterThan(0))
    expect(screen.queryByRole('region', { name: 'Event trail' })).toBeNull()
    expect(logsSubs(stream)).toHaveLength(0)
  })

  it('saysSoWhenNoOrderRequestIsLogged', async () => {
    stubStream(() => ({ data: { lines: [], total: 0 } }))
    renderAt('/monitoring/orders')
    expect(await screen.findByText('No order request in the last 24 hours')).toBeInTheDocument()
  })

  it('showsTheErrorWhenTheStoreIsUnavailable', async () => {
    stubStream(() => ({ error: { code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' } }))
    renderAt('/monitoring/orders')
    expect(await screen.findByText('LOG_STORE_UNAVAILABLE: The log store is unavailable')).toBeInTheDocument()
  })

  it('makesNoPeriodicRequestWhileTheOrdersPageIsOpen', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const stream = stubStream((s) => ({ data: isTrail(s) ? { lines: fixture.lines.slice(0, 2), total: 2 } : { lines: [fixture.lines[0]], total: 1 } }))
    renderAt('/monitoring/orders')
    const trail = await screen.findByRole('region', { name: 'Event trail' })
    await within(trail).findByText('order published to infra', {}, { timeout: 3000 })
    const settled = stream.fetch.mock.calls.length
    for (let i = 0; i < 20; i++) {
      await act(() => vi.advanceTimersByTimeAsync(15_000))
      stream.last().write(': ping\n\n')
    }
    // Five minutes: no new request of any kind, and still one open stream.
    expect(stream.fetch).toHaveBeenCalledTimes(settled)
    expect(stream.open()).toHaveLength(1)
  })
})
