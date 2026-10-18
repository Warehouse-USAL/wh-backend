import { act, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LiveProvider, useLive } from './live'

const wrapper = LiveProvider

describe('useLive', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('useLiveTicksEveryFiveSecondsOnlyWhileLive', () => {
    const { result } = renderHook(useLive, { wrapper })
    expect(result.current).toMatchObject({ live: true, range: '15m', tick: 0 })

    act(() => void vi.advanceTimersByTime(4999))
    expect(result.current.tick).toBe(0)
    act(() => void vi.advanceTimersByTime(1))
    expect(result.current.tick).toBe(1)

    act(() => result.current.toggle())
    act(() => void vi.advanceTimersByTime(20000))
    expect(result.current).toMatchObject({ live: false, tick: 1 })

    act(() => result.current.toggle())
    act(() => void vi.advanceTimersByTime(5000))
    expect(result.current.tick).toBe(2)
  })

  it('stopsTickingOnUnmount', () => {
    const { unmount } = renderHook(useLive, { wrapper })
    unmount()
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
