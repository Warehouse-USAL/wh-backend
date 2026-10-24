import { act, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LiveProvider, useLive } from './live'

const wrapper = LiveProvider

describe('useLive', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('togglesLiveAndRunsNoTimer', () => {
    const { result } = renderHook(useLive, { wrapper })
    expect(result.current).toMatchObject({ live: true, range: '15m' })
    expect(result.current).not.toHaveProperty('tick')
    // The push stream carries updates: nothing here ticks.
    expect(vi.getTimerCount()).toBe(0)

    act(() => result.current.toggle())
    expect(result.current.live).toBe(false)
    act(() => result.current.toggle())
    expect(result.current.live).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('setsTheRange', () => {
    const { result } = renderHook(useLive, { wrapper })
    act(() => result.current.setRange('6h'))
    expect(result.current.range).toBe('6h')
  })

  it('failsClearlyOutsideAProvider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const Probe = () => <p>{String(useLive().live)}</p>
    expect(() => render(<Probe />)).toThrow('useLive must be used inside a LiveProvider')
    render(<LiveProvider><Probe /></LiveProvider>)
    expect(screen.getByText('true')).toBeInTheDocument()
  })
})
