import type { ApiError } from '@/api/client'

/*
 * CONTRACT for the live, push-fed hooks. Pages import these names from here; the bodies live in
 * `./stream/hooks` (one SSE stream for the app, see `StreamProvider`; no polling). Tests of pages
 * mock this module.
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

export interface Live<T> {
  data: T
  /** True until the first frame (snapshot) of this subscription arrived. */
  loading: boolean
  error?: ApiError
}

// useStatus(): services and `dev`; useLogs(query): newest first, at most `query.limit`;
// useWsEvents(feed): newest first, at most 200.
export { useLogs, useStatus, useWsEvents } from './stream/hooks'
