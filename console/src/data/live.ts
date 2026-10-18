import { type ReactNode, createContext, createElement, useCallback, useContext, useEffect, useMemo, useState } from 'react'

export type Range = '5m' | '15m' | '1h' | '6h' | '24h'

export const RANGES: readonly Range[] = ['5m', '15m', '1h', '6h', '24h']

const TICK_MS = 5000

export interface LiveState {
  live: boolean
  range: Range
  toggle(): void
  setRange(r: Range): void
  /** Increments every 5 s while live; pages refetch when it changes. */
  tick: number
}

const LiveContext = createContext<LiveState | null>(null)

/** One live/range/tick state for the whole shell, so every page refreshes in step. */
export function LiveProvider({ children }: { children: ReactNode }) {
  const [live, setLive] = useState(true)
  const [range, setRange] = useState<Range>('15m')
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!live) return
    const id = setInterval(() => setTick((t) => t + 1), TICK_MS)
    return () => clearInterval(id)
  }, [live])
  const toggle = useCallback(() => setLive((l) => !l), [])
  const value = useMemo(() => ({ live, range, toggle, setRange, tick }), [live, range, toggle, tick])
  return createElement(LiveContext.Provider, { value }, children)
}

export function useLive(): LiveState {
  const state = useContext(LiveContext)
  if (!state) throw new Error('useLive must be used inside a LiveProvider')
  return state
}
