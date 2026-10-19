import { useMemo } from 'react'
import { type LogLine, type LogsQuery, logsParams } from '@/api/logs'
import { type WireStatus, toStatus } from '@/api/status'
import { type Range, useLive } from '../live'
import type { Live, ServiceStatus, WsEvent, WsFeed } from '../streamHooks'
import type { SubState } from './registry'
import { useSubscription } from './useStream'

const NO_LINES: LogLine[] = []
const NO_EVENTS: WsEvent[] = []
const NO_STATUS = { services: [] as ServiceStatus[], dev: false }
const STATUS = { kind: 'status' } as const

function live<T>(state: SubState, data: T): Live<T> {
  const loading = !state.received && !state.error
  return state.error ? { data, loading, error: state.error } : { data, loading }
}

/** Platform services and whether `dev` tools exist; replaced whenever a state or detail changes. */
export function useStatus(): Live<{ services: ServiceStatus[]; dev: boolean }> {
  const state = useSubscription(STATUS)
  const data = useMemo(() => (state.data ? toStatus(state.data as WireStatus) : NO_STATUS), [state.data])
  return live(state, data)
}

/** Newest first, at most `query.limit` (backend default 100, cap 500), over the shell's range. */
export function useLogs(query: Omit<LogsQuery, 'from' | 'to'>): Live<LogLine[]> {
  return useLogsIn(query, useLive().range)
}

/** {@link useLogs} over a fixed range; `null` subscribes to nothing (and stays loading). */
export function useLogsIn(query: Omit<LogsQuery, 'from' | 'to'> | null, range: Range): Live<LogLine[]> {
  const state = useSubscription(query && { kind: 'logs', ...logsParams(query), range })
  return live(state, (state.data as LogLine[] | undefined) ?? NO_LINES)
}

/** Newest first, at most 200; each event is pushed as it happens. */
export function useWsEvents(feed: WsFeed): Live<WsEvent[]> {
  const state = useSubscription({ kind: 'ws-events', feed })
  return live(state, (state.data as WsEvent[] | undefined) ?? NO_EVENTS)
}
