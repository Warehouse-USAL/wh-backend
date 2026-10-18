import { api } from './client'

export type LogService = 'backend' | 'bridge'
export type LogLevel = 'debug' | 'info' | 'warn' | 'error'

/** The five filters `POST /console/logs/query` takes; all optional. */
export interface LogsQuery {
  service?: LogService
  level?: LogLevel
  requestId?: string
  /** Free text, at most 200 characters, none of `" { } | \` ` or a line break. */
  text?: string
  from?: Date
  to?: Date
  /** The backend defaults to 100 and caps at 500. */
  limit?: number
}

/** A field the store did not return is null; `level` is lower case. */
export interface LogLine {
  time: string | null
  level: string | null
  service: string | null
  message: string | null
  requestId: string | null
}

export interface LogsResult {
  lines: LogLine[]
  total: number
}

/** The backend's cap on `limit`. */
export const MAX_LIMIT = 500
const DAY_MS = 86_400_000

interface WireLine {
  time: string | null
  level: string | null
  service: string | null
  message: string | null
  request_id: string | null
}

/** Newest first, as the backend answers. */
export async function queryLogs(q: LogsQuery, signal?: AbortSignal): Promise<LogsResult> {
  const body = JSON.stringify({
    service: q.service,
    level: q.level,
    request_id: q.requestId,
    text: q.text,
    from: q.from?.toISOString(),
    to: q.to?.toISOString(),
    limit: q.limit,
  })
  const { lines, total } = (await api('/console/logs/query', { method: 'POST', body, signal })) as { lines: WireLine[]; total: number }
  return { lines: lines.map(({ request_id, ...rest }) => ({ ...rest, requestId: request_id })), total }
}

/** Every line of one request across services, oldest first, over the last 24 hours. */
export async function trailFor(requestId: string, signal?: AbortSignal): Promise<LogLine[]> {
  const now = Date.now()
  const { lines } = await queryLogs({ requestId, from: new Date(now - DAY_MS), to: new Date(now), limit: MAX_LIMIT }, signal)
  return lines.reverse()
}
