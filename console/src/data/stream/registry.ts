import { ApiError } from '@/api/client'
import { type WireLine, toLogLine } from '@/api/logs'
import type { DataFrame, ErrorFrame, WireSubscription } from '@/api/stream'
import { type ConnectionHandlers, connect as openConnection } from './connection'

/** A subscription's kind and wire params, without its id. */
export type Spec = { kind: WireSubscription['kind'] } & Record<string, unknown>

/** What a hook sees of one subscription. */
export interface SubState {
  /** Replace kinds: the last payload. Logs and ws-events: the lines or events, newest first. */
  data?: unknown
  /** The last error frame (or why the stream is down), until the next data frame. */
  error?: ApiError
  /** A data frame arrived at least once. */
  received: boolean
  /** Has data, but the stream is reopening and this subscription's fresh snapshot is not back yet. */
  stale: boolean
}

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'paused' | 'stopped'

export interface StreamStatus {
  state: ConnectionState
  /** Epoch ms of the last frame received. */
  updatedAt?: number
  /** Why the stream is down (`reconnecting`, `stopped`). */
  error?: ApiError
}

export const INITIAL: SubState = { received: false, stale: false }

/** The backend keeps 200 events per feed; logs default to 100 lines when no limit is given. */
const WS_EVENTS_CAP = 200
const LOGS_DEFAULT_LIMIT = 100

/** Same params, same string: key order and undefined params do not matter. */
export function canonical(spec: Record<string, unknown>): string {
  return JSON.stringify(sorted(spec))
}

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted)
  if (typeof value !== 'object' || value === null) return value
  return Object.fromEntries(
    Object.keys(value)
      .filter((k) => (value as Record<string, unknown>)[k] !== undefined)
      .sort()
      .map((k) => [k, sorted((value as Record<string, unknown>)[k])]),
  )
}

const answered = (e: Entry) => e.state.received || e.state.error !== undefined

type Entry = { key: string; id: string; spec: Spec; refs: number; state: SubState; listeners: Set<() => void> }

type Options = { connect?: (subs: WireSubscription[], h: ConnectionHandlers) => () => void; debounceMs?: number }

/**
 * The console's one push stream and the subscriptions it carries. Hooks subscribe by canonical
 * params, ref-counted, so equal params from any number of widgets are one subscription. Changes to
 * the set are debounced and applied by reopening the stream with the new full set: the server
 * answers every subscription with a snapshot it already holds (sources are shared and cached
 * server side, and a late joiner gets the source's last result at once), so a reopen costs one
 * request and no store query, and it keeps one simple protocol instead of add/remove messages.
 * While paused (`live` off) the stream is closed and the last data stays.
 */
export class StreamRegistry {
  private readonly entries = new Map<string, Entry>()
  private readonly byId = new Map<string, Entry>()
  private readonly statusListeners = new Set<() => void>()
  private readonly connect: NonNullable<Options['connect']>
  private readonly debounceMs: number
  private status: StreamStatus = { state: 'idle' }
  private live = true
  private nextId = 1
  private timer: ReturnType<typeof setTimeout> | undefined
  private close: (() => void) | undefined
  private signature: string | undefined
  /** Bumped on every close, so frames of a closed stream still on their way are dropped. */
  private generation = 0
  private openedOnce = false

  constructor({ connect = openConnection, debounceMs = 250 }: Options = {}) {
    this.connect = connect
    this.debounceMs = debounceMs
  }

  /** Holds the subscription with these canonical params (see {@link canonical}) until the returned call. */
  subscribe(key: string, listener: () => void): () => void {
    let entry = this.entries.get(key)
    if (!entry) {
      const spec = JSON.parse(key) as Spec
      entry = { key, id: `${spec.kind}-${this.nextId++}`, spec, refs: 0, state: INITIAL, listeners: new Set() }
      this.entries.set(key, entry)
      this.byId.set(entry.id, entry)
    }
    const held = entry
    held.refs++
    held.listeners.add(listener)
    this.schedule()
    return () => {
      held.refs--
      held.listeners.delete(listener)
      this.schedule()
    }
  }

  get(key: string): SubState {
    return this.entries.get(key)?.state ?? INITIAL
  }

  getStatus = (): StreamStatus => this.status

  onStatus = (listener: () => void): (() => void) => {
    this.statusListeners.add(listener)
    return () => void this.statusListeners.delete(listener)
  }

  /** Off closes the stream at once and keeps the data; on reopens it at once. */
  setLive(live: boolean): void {
    if (live === this.live) return this.schedule()
    this.live = live
    clearTimeout(this.timer)
    this.flush()
  }

  /** Closes the stream (provider unmount); the next flush reopens it. */
  suspend(): void {
    clearTimeout(this.timer)
    this.timer = undefined
    this.stop()
  }

  private schedule(): void {
    clearTimeout(this.timer)
    this.timer = setTimeout(() => this.flush(), this.debounceMs)
  }

  private flush(): void {
    this.timer = undefined
    for (const entry of this.entries.values()) {
      if (entry.refs > 0) continue
      this.entries.delete(entry.key)
      this.byId.delete(entry.id)
    }
    const wanted = [...this.entries.values()]
    // Paused, the stream only opens for subscriptions that have nothing to show yet (a range
    // changed meanwhile), and closes again once each has its snapshot.
    if (wanted.length === 0 || (!this.live && wanted.every(answered))) return this.pause(wanted)
    const signature = wanted
      .map((e) => e.id)
      .sort()
      .join(',')
    if (this.close && signature === this.signature) return
    this.stop()
    this.signature = signature
    this.markStale(wanted)
    this.setStatus({ state: this.downState(), updatedAt: this.status.updatedAt })
    const generation = this.generation
    const current = () => generation === this.generation
    this.close = this.connect(
      wanted.map((e) => ({ ...e.spec, id: e.id })),
      {
        onOpen: () => {
          if (!current()) return
          this.openedOnce = true
          this.setStatus({ state: this.live ? 'connected' : 'paused', updatedAt: this.status.updatedAt })
        },
        onData: (event, frame) => {
          if (!current()) return
          this.onData(event, frame)
          if (!this.live && wanted.every(answered)) this.pause(wanted)
        },
        onError: (frame) => {
          if (!current()) return
          this.onError(frame)
          if (!this.live && wanted.every(answered)) this.pause(wanted)
        },
        onRetry: (error) => {
          if (!current()) return
          this.markStale(wanted, error)
          // Paused, one failed try is the answer: the error shows until Live is back on.
          if (!this.live) return this.pause(wanted, error)
          this.setStatus({ state: 'reconnecting', updatedAt: this.status.updatedAt, error })
        },
        onStop: (error) => {
          if (!current()) return
          this.close = undefined
          this.signature = undefined
          this.markStale([], error, wanted)
          this.setStatus({ state: 'stopped', updatedAt: this.status.updatedAt, error })
        },
      },
    )
  }

  private pause(wanted: Entry[], error?: ApiError): void {
    this.stop()
    for (const e of wanted) if (e.state.stale) this.update(e, { ...e.state, stale: false })
    this.setStatus({ state: wanted.length === 0 ? 'idle' : 'paused', updatedAt: this.status.updatedAt, ...(error && { error }) })
  }

  private downState(): ConnectionState {
    return !this.live ? 'paused' : this.openedOnce ? 'reconnecting' : 'connecting'
  }

  private stop(): void {
    this.generation++
    this.close?.()
    this.close = undefined
    this.signature = undefined
  }

  /** Entries with data turn stale; those without show `error` (when given) instead of loading for ever. */
  private markStale(entries: Entry[], error?: ApiError, failed: Entry[] = entries): void {
    for (const e of entries) if (e.state.received && !e.state.stale) this.update(e, { ...e.state, stale: true })
    if (error) for (const e of failed) if (!e.state.received) this.update(e, { ...e.state, error })
  }

  private onData(event: 'snapshot' | 'update', frame: DataFrame): void {
    this.touch()
    const entry = this.byId.get(frame.id)
    if (!entry) return
    const replace = event === 'snapshot' || frame.mode === 'replace'
    this.update(entry, { data: reduce(entry, frame.data, replace), received: true, stale: false })
  }

  private onError(frame: ErrorFrame): void {
    this.touch()
    const entry = this.byId.get(frame.id)
    if (entry) this.update(entry, { ...entry.state, stale: false, error: new ApiError(0, frame.code, frame.message) })
  }

  private touch(): void {
    this.setStatus({ ...this.status, updatedAt: Date.now() })
  }

  private update(entry: Entry, state: SubState): void {
    entry.state = state
    entry.listeners.forEach((l) => l())
  }

  private setStatus(status: StreamStatus): void {
    this.status = status
    this.statusListeners.forEach((l) => l())
  }
}

/** Logs: newest first, appends arrive oldest first. ws-events: newest first, one event per append. */
function reduce(entry: Entry, data: unknown, replace: boolean): unknown {
  const { kind } = entry.spec
  if (kind !== 'logs' && kind !== 'ws-events') return data
  const items = kind === 'logs' ? (data as { lines: WireLine[] }).lines.map(toLogLine) : (data as { events: unknown[] }).events
  const cap = kind === 'logs' ? ((entry.spec.limit as number | undefined) ?? LOGS_DEFAULT_LIMIT) : WS_EVENTS_CAP
  const previous = replace ? [] : (entry.state.data as unknown[] | undefined) ?? []
  return [...(replace ? items : items.reverse()), ...previous].slice(0, cap)
}
