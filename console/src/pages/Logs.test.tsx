import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import fixture from '@/api/__fixtures__/logs-query.json'
import { setSession } from '@/api/client'
import { LiveProvider } from '@/data/live'
import { jsonResponse } from '@/test/http'
import Logs from './Logs'

type Body = Record<string, unknown>
type Answer = (body: Body) => Response

/** Answers every logs query through `answer`; returns the bodies it was asked. */
function stubLogs(answer: Answer = () => jsonResponse(200, fixture)) {
  const bodies: Body[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as Body
      bodies.push(body)
      return answer(body)
    }),
  )
  return bodies
}

const renderLogs = (url = '/infra/logs') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <LiveProvider>
        <Logs />
      </LiveProvider>
    </MemoryRouter>,
  )

const stream = () => within(screen.getByText('Stream').closest('div.rounded-lg') as HTMLElement)

describe('Logs', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))
  afterEach(() => vi.useRealTimers())

  it('clickingAReqValueFiltersToThatRequest', async () => {
    const bodies = stubLogs((b) => jsonResponse(200, b.request_id ? { lines: [fixture.lines[2]], total: 1 } : fixture))
    renderLogs()
    await userEvent.click(await screen.findByRole('button', { name: 'req=t23bad' }))

    expect(await screen.findByRole('button', { name: 'Clear request id filter t23bad' })).toBeInTheDocument()
    expect(bodies.at(-1)).toMatchObject({ request_id: 't23bad' })
    expect(stream().queryByRole('button', { name: 'req=t23order1' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Clear request id filter t23bad' }))
    expect(await stream().findByRole('button', { name: 'req=t23order1' })).toBeInTheDocument()
    expect(bodies.at(-1)).not.toHaveProperty('request_id')
  })

  it('facetsCountTheReturnedLines', async () => {
    stubLogs()
    renderLogs()
    await screen.findByText('Facets')
    const facets = within(screen.getByText('Facets').closest('div.rounded-lg') as HTMLElement)
    expect(facets.getByText('4 returned lines')).toBeInTheDocument()
    const row = (name: string) => facets.getAllByText(name)[0].parentElement as HTMLElement
    expect(row('backend')).toHaveTextContent('backend2')
    expect(row('bridge')).toHaveTextContent('bridge2')
    expect(row('info')).toHaveTextContent('info2')
    expect(row('warn')).toHaveTextContent('warn2')
  })

  it('saysSoWhenTheFacetsOnlyCoverTheNewestLines', async () => {
    const line = fixture.lines[0]
    stubLogs(() => jsonResponse(200, { lines: Array.from({ length: 200 }, () => line), total: 200 }))
    renderLogs()
    expect(await screen.findByText('newest 200 lines, more may match')).toBeInTheDocument()
  })

  it('storeUnavailableShowsTheErrorNotAnEmptyList', async () => {
    stubLogs(() => jsonResponse(502, { error: { code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' } }))
    renderLogs()
    expect(await screen.findByRole('alert')).toHaveTextContent('LOG_STORE_UNAVAILABLE: The log store is unavailable')
    expect(screen.queryByText('No log lines match')).toBeNull()

    const bodies = stubLogs()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Stream')).toBeInTheDocument()
    expect(bodies).toHaveLength(1)
  })

  it('showsAnEmptyStateAndNoVolume', async () => {
    stubLogs(() => jsonResponse(200, { lines: [], total: 0 }))
    renderLogs()
    expect(await screen.findByText('No log lines match')).toBeInTheDocument()
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
  })

  it('startsFromTheReqInTheUrl', async () => {
    const bodies = stubLogs()
    renderLogs('/infra/logs?req=abc-1')
    await screen.findByText('Stream')
    expect(bodies[0]).toMatchObject({ request_id: 'abc-1' })
    expect(screen.getByRole('button', { name: 'Clear request id filter abc-1' })).toBeInTheDocument()
  })

  it('filtersByServiceLevelAndText', async () => {
    const bodies = stubLogs()
    renderLogs()
    await screen.findByText('Stream')
    await userEvent.click(within(screen.getByRole('group', { name: 'Service' })).getByRole('button', { name: 'bridge' }))
    await userEvent.click(within(screen.getByRole('group', { name: 'Level' })).getByRole('button', { name: 'warn' }))
    await userEvent.type(screen.getByLabelText('Search log text'), ' retrying {Enter}')
    await screen.findByText('Stream')
    expect(bodies.at(-1)).toMatchObject({ service: 'bridge', level: 'warn', text: 'retrying', limit: 200 })
    await userEvent.click(within(screen.getByRole('group', { name: 'Service' })).getByRole('button', { name: 'all' }))
    await screen.findByText('Stream')
    expect(bodies.at(-1)).not.toHaveProperty('service')
  })

  it('stacksTheVolumeByLevelAndBucketsByTheRangeStep', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-18T21:23:30Z') })
    stubLogs()
    renderLogs()
    const chart = await screen.findByRole('img', { name: /Log volume/ })
    // 15m range, 15 s buckets: info lands in the :15 and :00 buckets, warn only in :00 (2 lines), so three bars.
    expect(chart.querySelectorAll('rect')).toHaveLength(3)
    expect(chart).toHaveAccessibleName(/info 1, warn 2/)
  })

  it('givesEachBucketItsOwnBar', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-18T21:23:30Z') })
    const earlier = { ...fixture.lines[0], time: '2026-10-18T21:22:05Z' }
    stubLogs(() => jsonResponse(200, { lines: [fixture.lines[0], earlier], total: 2 }))
    renderLogs()
    const chart = await screen.findByRole('img', { name: /Log volume/ })
    expect(chart.querySelectorAll('rect')).toHaveLength(2)
  })

  it('keepsThePreviousLinesWhileATickRefreshes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let release: () => void = () => undefined
    const bodies = stubLogs(() => jsonResponse(200, fixture))
    renderLogs()
    await screen.findByText('Stream')
    const first = bodies.length
    vi.mocked(fetch).mockImplementationOnce(() => new Promise((resolve) => (release = () => resolve(jsonResponse(200, fixture)))))
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(bodies.length).toBe(first)
    expect(screen.queryByRole('status')).toBeNull()
    expect(stream().getByRole('button', { name: 'req=t23order1' })).toBeInTheDocument()
    await act(async () => release())
    expect(stream().getByRole('button', { name: 'req=t23order1' })).toBeInTheDocument()
  })

  it('toleratesFieldsTheStoreDidNotReturn', async () => {
    const bare = { time: null, level: null, service: null, message: null, request_id: null }
    stubLogs(() => jsonResponse(200, { lines: [bare, { ...bare, level: 'error', time: 'not a time', message: 'boom' }], total: 2 }))
    renderLogs()
    expect(await screen.findByText('boom')).toBeInTheDocument()
    expect(stream().getAllByText('-')).toHaveLength(2)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
    expect(stream().queryByRole('button', { name: /^req=/ })).toBeNull()
  })

  it('expandsALongMessageAndNeverRendersHtml', async () => {
    const message = `<img src=x onerror=alert(1)> ${'long '.repeat(60)}`
    stubLogs(() => jsonResponse(200, { lines: [{ ...fixture.lines[0], message }], total: 1 }))
    renderLogs()
    const row = (await screen.findByText(/onerror/)).parentElement as HTMLElement
    expect(row.querySelector('img')).toBeNull()
    await userEvent.click(within(row).getByRole('button', { name: 'more' }))
    expect(within(row).getByRole('button', { name: 'less' })).toBeInTheDocument()
  })
})
