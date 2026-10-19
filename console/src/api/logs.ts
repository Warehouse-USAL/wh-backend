export type LogService = 'backend' | 'bridge'
export type LogLevel = 'debug' | 'info' | 'warn' | 'error'

/** The filters of `POST /console/logs/query` (and of a logs stream subscription); all optional. */
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

/** The backend's cap on `limit`. */
export const MAX_LIMIT = 500

/** A line as the backend sends it (REST and stream). */
export interface WireLine {
  time: string | null
  level: string | null
  service: string | null
  message: string | null
  request_id: string | null
}

export function toLogLine({ request_id, ...rest }: WireLine): LogLine {
  return { ...rest, requestId: request_id }
}

/** A logs stream subscription's filters, snake_case (`from`/`to` come from its range). */
export function logsParams(q: Omit<LogsQuery, 'from' | 'to'>): Record<string, unknown> {
  return { service: q.service, level: q.level, request_id: q.requestId, text: q.text, limit: q.limit }
}
