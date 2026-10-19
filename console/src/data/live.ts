import { type ReactNode, createContext, createElement, useCallback, useContext, useMemo, useState } from 'react'

export type Range = '5m' | '15m' | '1h' | '6h' | '24h'

export const RANGES: readonly Range[] = ['5m', '15m', '1h', '6h', '24h']

export interface LiveState {
  /** While on, the push stream stays open (see `StreamProvider`); off closes it and keeps the data. */
  live: boolean
  range: Range
  toggle(): void
  setRange(r: Range): void
}

const LiveContext = createContext<LiveState | null>(null)

/** One live/range state for the whole shell. */
export function LiveProvider({ children }: { children: ReactNode }) {
  const [live, setLive] = useState(true)
  const [range, setRange] = useState<Range>('15m')
  const toggle = useCallback(() => setLive((l) => !l), [])
  const value = useMemo(() => ({ live, range, toggle, setRange }), [live, range, toggle])
  return createElement(LiveContext.Provider, { value }, children)
}

export function useLive(): LiveState {
  const state = useContext(LiveContext)
  if (!state) throw new Error('useLive must be used inside a LiveProvider')
  return state
}
