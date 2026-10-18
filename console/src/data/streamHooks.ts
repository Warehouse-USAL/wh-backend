import type { ApiError } from '@/api/client'
import type { LogLine, LogsQuery } from '@/api/logs'

/*
 * CONTRACT for the live, push-fed hooks. Pages import these names from here; Task 23e replaces the
 * bodies with the SSE stream client (no polling) and moves each hook to its own module re-exported
 * from this file. Tests of pages mock this module.
 */

export type ServiceState = 'UP' | 'DOWN' | 'UNKNOWN'

/** One platform service as `GET /console/status` reports it. */
export interface ServiceStatus {
  name: string
  state: ServiceState
  /** Short reason, never a URL. */
  detail: string | null
  /** Set for the backend only. */
  uptimeMs: number | null
}

export interface WsEvent {
  at: string
  kind: 'connect' | 'push' | 'close' | 'reject'
  detail: string
}

export type WsFeed = 'orders' | 'user-orders' | 'vehicles' | 'stock-alerts'

interface Live<T> {
  data: T
  /** True until the first frame (snapshot) of this subscription arrived. */
  loading: boolean
  error?: ApiError
}

const idle = <T,>(data: T): Live<T> => ({ data, loading: true })

/** Platform services and whether `dev` tools exist (`dev` comes from the same payload). */
export function useStatus(): Live<{ services: ServiceStatus[]; dev: boolean }> {
  return idle({ services: [], dev: false })
}

/** Newest first, at most `query.limit` (backend cap 500); updates arrive as the backend logs. */
export function useLogs(_query: Omit<LogsQuery, 'from' | 'to'>): Live<LogLine[]> {
  return idle([])
}

/** Newest first, at most 200; each event is pushed as it happens. */
export function useWsEvents(_feed: WsFeed): Live<WsEvent[]> {
  return idle([])
}
