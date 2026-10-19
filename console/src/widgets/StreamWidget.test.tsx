import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StreamWidget from './StreamWidget'

describe('StreamWidget', () => {
  it('rendersRowsWithToneAndLiveIndicator', () => {
    render(
      <StreamWidget title="Event buffer" live rows={[
        { time: '14:02:12.516', tag: 'push', tone: 'info', text: <b>order.updated</b> },
        { time: '14:01:40.331', tag: 'close', tone: 'warn', text: 'gone' },
        { time: '14:01:20.044', tag: 'reject', tone: 'error', text: 'denied' },
        { time: '14:01:02.900', tag: 'connect', tone: 'ok', text: 'hello' },
        { time: '14:00:00.000', tag: 'misc', text: 'plain' },
      ]} />,
    )
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.getByText('order.updated')).toBeInTheDocument()
    expect(screen.getByText('push')).toHaveClass('text-blue')
    expect(screen.getByText('close')).toHaveClass('text-wn')
    expect(screen.getByText('reject')).toHaveClass('text-er')
    expect(screen.getByText('connect')).toHaveClass('text-ok')
    expect(screen.getByText('misc')).toHaveClass('text-mu')
  })

  it('showsEmptyStateAndNoLiveBadgeByDefault', () => {
    render(<StreamWidget title="Event buffer" rows={[]} />)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
    expect(screen.queryByText('Live')).toBeNull()
  })
})
