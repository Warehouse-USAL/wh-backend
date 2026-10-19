import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { LiveProvider } from '@/data/live'
import { useStatus } from '@/data/streamHooks'
import { jsonResponse } from '@/test/http'
import { StreamWrapper, byKind, fakeStream } from '@/test/stream'
import Header from './Header'

const renderHeader = (props: Partial<Parameters<typeof Header>[0]> = {}) =>
  render(
    <LiveProvider>
      <Header crumb="Monitoring" title="Orders" {...props} />
    </LiveProvider>,
  )

describe('Header', () => {
  it('showsCrumbTitleChipsAndNoTabsByDefault', () => {
    renderHeader({ chips: ['env staging'] })

    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(screen.getByText('Monitoring /')).toBeInTheDocument()
    expect(screen.getByText('env staging')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Endpoints' })).toBeNull()
  })

  it('marksTheFirstTabThenTheControlledOne', async () => {
    const onTab = vi.fn()
    const { rerender } = renderHeader({ tabs: ['Endpoints', 'Live feed'], onTab })
    expect(screen.getByRole('tab', { name: 'Endpoints' })).toHaveAttribute('aria-selected', 'true')

    await userEvent.setup().click(screen.getByRole('tab', { name: 'Live feed' }))
    expect(onTab).toHaveBeenCalledWith('Live feed')

    rerender(
      <LiveProvider>
        <Header crumb="Monitoring" title="Orders" tabs={['Endpoints', 'Live feed']} tab="Live feed" onTab={onTab} />
      </LiveProvider>,
    )
    expect(screen.getByRole('tab', { name: 'Live feed' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Endpoints' }))
    expect(onTab).toHaveBeenLastCalledWith('Endpoints')
  })

  it('togglesLiveAndChangesTheRange', async () => {
    renderHeader()
    const user = userEvent.setup()
    const live = screen.getByRole('button', { name: 'Live updates' })
    expect(live).toHaveAttribute('aria-pressed', 'true')

    await user.click(live)
    expect(live).toHaveAttribute('aria-pressed', 'false')

    const range = screen.getByRole('combobox', { name: 'Time range' })
    expect(range).toHaveValue('15m')
    await user.selectOptions(range, '1h')
    expect(range).toHaveValue('1h')
  })

  it('showsTheStreamStateAndTheTimeOfTheLastFrameNextToLive', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date(2026, 9, 18, 14, 2, 11) })
    vi.spyOn(Math, 'random').mockReturnValue(1)
    setSession({ token: 'tok', email: 'root@x.io' })
    let refusals = 1
    const stream = fakeStream({ refuse: () => (refusals-- > 0 ? jsonResponse(429, { error: { code: 'STREAM_LIMIT', message: 'Hay demasiados streams abiertos' } }) : undefined) })
    const Subscriber = () => (useStatus(), null)
    render(
      <StreamWrapper>
        <Header crumb="Monitoring" title="Orders" />
        <Subscriber />
      </StreamWrapper>,
    )
    const indicator = await screen.findByRole('status', { name: 'Stream status' })
    // The limit answer is visible, and the stream retries on its own.
    await waitFor(() => expect(indicator).toHaveTextContent('reconnecting (STREAM_LIMIT)'))
    expect(indicator).toHaveAttribute('title', 'STREAM_LIMIT: Hay demasiados streams abiertos')
    expect(indicator).toHaveClass('text-wn')
    await waitFor(() => expect(indicator).toHaveTextContent(/^connected$/), { timeout: 3000 })

    act(() => stream.last().send(byKind('status'), { dev: false, services: [] }, 'snapshot'))
    await waitFor(() => expect(indicator).toHaveTextContent(/^connected · Updated 14:02:11$/))

    await userEvent.setup().click(screen.getByRole('button', { name: 'Live updates' }))
    expect(indicator).toHaveTextContent(/^paused · Updated 14:02:11$/)
    vi.useRealTimers()
  })

  it('showsNoStreamStateWhileNothingIsSubscribed', () => {
    renderHeader()
    expect(screen.queryByRole('status', { name: 'Stream status' })).toBeNull()
  })

  it('showsActionsWithoutTheTimeControlsWhenTheyAreOff', () => {
    render(<Header crumb="Domains" title="Users" timeControls={false} actions={<button type="button">New user</button>} />)

    expect(screen.getByRole('button', { name: 'New user' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Live updates' })).toBeNull()
    expect(screen.queryByRole('combobox', { name: 'Time range' })).toBeNull()
  })

  it('keepsNoTimeControlsWhenOffEvenWithoutActions', () => {
    render(<Header crumb="Domains" title="Restock" timeControls={false} />)

    expect(screen.queryByRole('button', { name: 'Live updates' })).toBeNull()
  })
})
