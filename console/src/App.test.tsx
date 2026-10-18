import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { api, getSession, setSession } from './api/client'
import { NAV } from './layout/nav'
import { jsonResponse, mockFetch } from './test/http'
import { fakeStream } from './test/stream'

const renderApp = (path = '/') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )

describe('App', () => {
  beforeEach(() => setSession(null))

  it('asksForLoginWithoutASession', () => {
    renderApp()

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('showsTheShellForASessionAndSignsOut', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    renderApp()

    expect(screen.getByText('root@x.io')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }))

    expect(getSession()).toBeNull()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('redirectsTheRootToTheOverview', () => {
    setSession({ token: 't', email: 'root@x.io' })
    renderApp()

    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
  })

  it('rendersEachNavPageInsideTheShell', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    renderApp('/overview')

    await userEvent.setup().click(screen.getByRole('link', { name: 'Edge (prod)' }))

    expect(screen.getByRole('heading', { name: 'Edge (prod)' })).toBeInTheDocument()
    expect(screen.getByText('Infrastructure /')).toBeInTheDocument()
  })

  it('rendersDomainsPagesAsEntityTables', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    mockFetch(jsonResponse(200, { vehicles: [{ id: 'veh-agv-01', name: 'AGV-01' }] }))
    renderApp('/domains/vehicles')

    expect(screen.getByText('Domains /')).toBeInTheDocument()
    expect(await screen.findByRole('cell', { name: 'veh-agv-01' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New vehicle' })).toBeInTheDocument()
  })

  it('rendersTheLogsPageWithTheRequestIdFromTheUrl', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    const stream = fakeStream({ answer: () => ({ data: { lines: [], total: 0 } }) })
    renderApp('/infra/logs?req=abc')

    expect(await screen.findByText('No log lines match')).toBeInTheDocument()
    expect(stream.last().subscriptions).toEqual([expect.objectContaining({ kind: 'logs', request_id: 'abc' })])
  })

  it('rendersMonitoringPagesForTheNavDomainsAndKeepsWebSocketsAPlaceholder', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => new Response(null, { status: 404 })))
    renderApp('/monitoring/orders')

    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(await screen.findByText('API description unavailable')).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('link', { name: 'WebSockets' }))
    expect(screen.getByRole('heading', { name: 'WebSockets' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  const MONITORING = (NAV.find((s) => s.section === 'Monitoring')?.items ?? []).filter((i) => i.label !== 'WebSockets')

  it('hasNineMonitoringDomainPages', () => {
    expect(MONITORING).toHaveLength(9)
  })

  it.each(MONITORING.map((i) => [i.label, i.path]))('monitoringNavItem%sGetsItsDomainPage', async (label, path) => {
    setSession({ token: 't', email: 'root@x.io' })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => new Response(null, { status: 404 })))
    renderApp(path)

    expect(screen.getByRole('heading', { name: label })).toBeInTheDocument()
    // Only a domain page loads the API description; a placeholder would not.
    expect(await screen.findByText('API description unavailable')).toBeInTheDocument()
  })

  it('showsNotFoundInsideTheShellForUnknownPaths', () => {
    setSession({ token: 't', email: 'root@x.io' })
    renderApp('/nope')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Console' })).toBeInTheDocument()
  })

  it('repromptsLoginWhenTheApiReports401', async () => {
    setSession({ token: 'expired', email: 'root@x.io' })
    renderApp()
    mockFetch(new Response(null, { status: 401 }))

    await act(() => api('/users').catch(() => undefined))

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired')
  })
})
