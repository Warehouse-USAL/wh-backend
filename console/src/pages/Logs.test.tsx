import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import fixture from '@/api/__fixtures__/logs-query.json'
import { setSession } from '@/api/client'
import type { WireSubscription } from '@/api/stream'
import { type Answer, StreamWrapper, byKind, fakeStream } from '@/test/stream'
import { formatTime } from '@/widgets/scale'
import Logs from './Logs'

type Body = WireSubscription

const ok = (data: unknown): Answer => ({ data })
const fail = (body: { error: { code: string; message: string } }): Answer => body

/**
 * A push backend answering each logs subscription's snapshot through `answer`; returns the logs
 * subscriptions it was asked for, one per stream opened (each filter change opens a new one).
 */
function stubLogs(answer: (body: Body) => Answer = () => ok(fixture)) {
  const bodies: Body[] = []
  const server = fakeStream({
    answer: (s) => {
      if (s.kind !== 'logs') return undefined
      bodies.push(s)
      return answer(s)
    },
  })
  return Object.assign(bodies, { server })
}

const renderLogs = (url = '/infra/logs') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <StreamWrapper>
        <Logs />
      </StreamWrapper>
    </MemoryRouter>,
  )

const firstLabel = (chart: HTMLElement) => chart.parentElement?.nextElementSibling?.firstElementChild?.textContent
const stream = () => within(screen.getByRole('region', { name: 'Stream' }))

describe('Logs', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))
  afterEach(() => vi.useRealTimers())

  it('clickingAReqValueFiltersToThatRequest', async () => {
    const bodies = stubLogs((b) => ok(b.request_id ? { lines: [fixture.lines[2]], total: 1 } : fixture))
    renderLogs()
    await userEvent.click(await screen.findByRole('button', { name: 'req=t23bad' }))

    expect(await screen.findByRole('button', { name: 'Clear request id filter t23bad' })).toBeInTheDocument()
    await vi.waitFor(() => expect(bodies.at(-1)).toMatchObject({ request_id: 't23bad' }))
    await vi.waitFor(() => expect(stream().getByRole('button', { name: 'req=t23bad' })).toBeInTheDocument())
    expect(stream().queryByRole('button', { name: 'req=t23order1' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Clear request id filter t23bad' }))
    await vi.waitFor(() => expect(stream().getByRole('button', { name: 'req=t23order1' })).toBeInTheDocument())
    expect(bodies.at(-1)).not.toHaveProperty('request_id')
  })

  it('facetsCountTheReturnedLines', async () => {
    stubLogs()
    renderLogs()
    await screen.findByText('Facets')
    const facets = within(screen.getByRole('region', { name: 'Facets' }))
    expect(facets.getByText('4 returned lines')).toBeInTheDocument()
    const row = (name: string) => facets.getAllByText(name)[0].parentElement as HTMLElement
    expect(row('backend')).toHaveTextContent('backend2')
    expect(row('bridge')).toHaveTextContent('bridge2')
    expect(row('info')).toHaveTextContent('info2')
    expect(row('warn')).toHaveTextContent('warn2')
  })

  it('saysSoWhenTheFacetsOnlyCoverTheNewestLines', async () => {
    const line = fixture.lines[0]
    stubLogs(() => ok({ lines: Array.from({ length: 200 }, () => line), total: 200 }))
    renderLogs()
    expect(await screen.findByText('newest 200 lines, more may match')).toBeInTheDocument()
  })

  it('capsTheVolumeToTheSpanTheNewestLinesCover', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-18T21:23:30Z') })
    const line = (time: string) => ({ ...fixture.lines[0], time })
    // 199 lines in the last minute and the 200th at 21:21:10: nothing older was fetched.
    const lines = [...Array.from({ length: 199 }, () => line('2026-10-18T21:23:20Z')), line('2026-10-18T21:21:10Z')]
    stubLogs(() => ok({ lines, total: 200 }))
    renderLogs()
    const chart = await screen.findByRole('img', { name: /Log volume/ })
    expect(screen.getByText('by level, newest 200 lines only')).toBeInTheDocument()
    // 21:21:00 to 21:23:15 in 15 s buckets: 10 buckets, not the range's 60.
    expect(firstLabel(chart)).toBe(formatTime(Date.parse('2026-10-18T21:21:00Z'), true))
  })

  it('keepsTheFullAxisAndIncludesTheFirstPartialBucketWhenNotCapped', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-18T21:23:40Z') })
    // 15m range at 21:23:40: the window starts 21:08:40, inside the partial 21:08:30 bucket.
    const old = { ...fixture.lines[0], time: '2026-10-18T21:08:42Z' }
    stubLogs(() => ok({ lines: [old, fixture.lines[0]], total: 2 }))
    renderLogs()
    const chart = await screen.findByRole('img', { name: /Log volume/ })
    expect(screen.getByText('by level')).toBeInTheDocument()
    expect(chart.querySelectorAll('rect')).toHaveLength(2)
    expect(firstLabel(chart)).toBe(formatTime(Date.parse('2026-10-18T21:08:30Z'), false))
  })

  it('ignoresAnInvalidRequestIdInTheUrlWithAHintAndSendsNothingForIt', async () => {
    const bodies = stubLogs()
    renderLogs('/infra/logs?req=bad%20id%22')
    expect(await screen.findByRole('note')).toHaveTextContent('Ignored the request id')
    await screen.findByRole('region', { name: 'Stream' })
    expect(bodies[0]).not.toHaveProperty('request_id')
    expect(screen.queryByRole('button', { name: /^Clear request id/ })).toBeNull()
  })

  it('refusesForbiddenSearchCharactersWithAHintBeforeSending', async () => {
    const bodies = stubLogs()
    renderLogs()
    await screen.findByRole('region', { name: 'Stream' })
    await userEvent.type(screen.getByLabelText('Search log text'), 'a|b{Enter}')
    expect(screen.getByRole('note')).toHaveTextContent('cannot contain')
    expect(bodies).toHaveLength(1)
    await userEvent.clear(screen.getByLabelText('Search log text'))
    await userEvent.type(screen.getByLabelText('Search log text'), 'ok{Enter}')
    expect(screen.queryByRole('note')).toBeNull()
    await vi.waitFor(() => expect(bodies.at(-1)).toMatchObject({ text: 'ok' }))
  })

  it('storeUnavailableShowsTheErrorNotAnEmptyList', async () => {
    const bodies = stubLogs(() => fail({ error: { code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' } }))
    renderLogs()
    expect(await screen.findByRole('alert')).toHaveTextContent('LOG_STORE_UNAVAILABLE: The log store is unavailable')
    expect(screen.queryByText('No log lines match')).toBeNull()
    expect(screen.queryByRole('region', { name: 'Stream' })).toBeNull()

    // The server retries the store itself; its first good answer is the snapshot.
    act(() => bodies.server.last().send(byKind('logs'), fixture, 'snapshot'))
    expect(await screen.findByRole('region', { name: 'Stream' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(bodies.server.fetch).toHaveBeenCalledTimes(1)
  })

  it('anOutageAfterLinesKeepsThemUnderTheError', async () => {
    const bodies = stubLogs()
    renderLogs()
    await screen.findByRole('region', { name: 'Stream' })
    act(() => bodies.server.last().error(byKind('logs'), 'LOG_STORE_UNAVAILABLE', 'The log store is unavailable'))
    expect(await screen.findByRole('alert')).toHaveTextContent('LOG_STORE_UNAVAILABLE')
    expect(stream().getByRole('button', { name: 'req=t23order1' })).toBeInTheDocument()
  })

  it('showsAnEmptyStateAndNoVolume', async () => {
    stubLogs(() => ok({ lines: [], total: 0 }))
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
    stubLogs(() => ok({ lines: [fixture.lines[0], earlier], total: 2 }))
    renderLogs()
    const chart = await screen.findByRole('img', { name: /Log volume/ })
    expect(chart.querySelectorAll('rect')).toHaveLength(2)
  })

  it('newLinesArriveOnTopWithoutAnyRequestAndTheOldOnesStay', async () => {
    const bodies = stubLogs()
    renderLogs()
    await screen.findByRole('region', { name: 'Stream' })
    const fresh = { time: '2026-10-18T21:23:40Z', level: 'error', service: 'backend', message: 'boom just now', request_id: 'r-new' }
    act(() => bodies.server.last().send(byKind('logs'), { lines: [fresh], total: 1 }))
    expect(await stream().findByText('boom just now')).toBeInTheDocument()
    const rows = within(screen.getByRole('region', { name: 'Stream' })).getAllByRole('button', { name: /^req=/ })
    expect(rows.map((r) => r.textContent)).toEqual(['req=r-new', 'req=t23order1', 'req=t23bad'])
    expect(screen.getByText('5 returned lines')).toBeInTheDocument()
    expect(bodies.server.fetch).toHaveBeenCalledTimes(1)
  })

  it('toleratesFieldsTheStoreDidNotReturn', async () => {
    const bare = { time: null, level: null, service: null, message: null, request_id: null }
    stubLogs(() => ok({ lines: [bare, { ...bare, level: 'error', time: 'not a time', message: 'boom' }], total: 2 }))
    renderLogs()
    expect(await screen.findByText('boom')).toBeInTheDocument()
    expect(stream().getAllByText('-')).toHaveLength(2)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
    expect(stream().queryByRole('button', { name: /^req=/ })).toBeNull()
  })

  it('expandsALongMessageAndNeverRendersHtml', async () => {
    const message = `<img src=x onerror=alert(1)> ${'long '.repeat(60)}`
    stubLogs(() => ok({ lines: [{ ...fixture.lines[0], message }], total: 1 }))
    renderLogs()
    const row = (await screen.findByText(/onerror/)).parentElement as HTMLElement
    expect(row.querySelector('img')).toBeNull()
    await userEvent.click(within(row).getByRole('button', { name: 'more' }))
    expect(within(row).getByRole('button', { name: 'less' })).toBeInTheDocument()
  })
})
