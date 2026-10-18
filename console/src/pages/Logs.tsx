import { X } from 'lucide-react'
import { type FormEvent, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { errorText } from '@/api/client'
import { type LogLevel, type LogLine, type LogService, type LogsResult, queryLogs } from '@/api/logs'
import { Button } from '@/components/ui/button'
import { type Range, useLive } from '@/data/live'
import { RANGE_MS, stepFor } from '@/data/useMetric'
import { stepMs } from '@/api/metrics'
import Header from '@/layout/Header'
import { cn } from '@/lib/utils'
import BarsWidget from '@/widgets/BarsWidget'
import Card from '@/widgets/Card'
import { PALETTE, type Series, formatClock } from '@/widgets/scale'

const SERVICES: LogService[] = ['backend', 'bridge']
const LEVELS: LogLevel[] = ['debug', 'info', 'warn', 'error']
const LIMIT = 200
const LEVEL_COLOR: Record<LogLevel, string> = { debug: PALETTE.gray, info: PALETTE.blue, warn: PALETTE.amber, error: PALETTE.red }
const LEVEL_TONE: Record<string, string> = { error: 'text-er', warn: 'text-wn', info: 'text-blue' }
const ROW_TONE: Record<string, string> = { error: 'bg-er/10', warn: 'bg-wn/10' }
const LONG = 160
// The backend's rules (LogsQueryService): anything else is a 400, so say so before sending.
const REQUEST_ID = /^[A-Za-z0-9_-]{1,64}$/
const FORBIDDEN_TEXT = /["{}|\\`\r\n]/

type State = { key: string; data?: LogsResult & { at: number }; error?: unknown }

/** Infrastructure / Logs: search, filter and follow backend and bridge log lines. */
export default function Logs() {
  const { range, tick } = useLive()
  const [params, setParams] = useSearchParams()
  const rawRequest = params.get('req') ?? undefined
  const requestId = rawRequest && REQUEST_ID.test(rawRequest) ? rawRequest : undefined
  const [hint, setHint] = useState<string>()
  const [service, setService] = useState<LogService>()
  const [level, setLevel] = useState<LogLevel>()
  const [text, setText] = useState('')
  const [draft, setDraft] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<State>({ key: '' })

  const key = JSON.stringify([range, service, level, requestId, text, attempt])
  useEffect(() => {
    const controller = new AbortController()
    const at = Date.now()
    const query = { service, level, requestId, text: text || undefined, from: new Date(at - RANGE_MS[range]), to: new Date(at), limit: LIMIT }
    queryLogs(query, controller.signal).then(
      (data) => !controller.signal.aborted && setState({ key, data: { ...data, at } }),
      (error: unknown) => !controller.signal.aborted && setState({ key, error }),
    )
    return () => controller.abort()
    // `key` covers every filter; a tick refetches under the same key and the previous lines stay meanwhile.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, tick])

  const setRequest = (id?: string) => setParams(id ? { req: id } : {}, { replace: true })
  const search = (e: FormEvent) => {
    e.preventDefault()
    const next = draft.trim()
    if (FORBIDDEN_TEXT.test(next)) return void setHint('Search text cannot contain " { } | \\ ` or line breaks.')
    setHint(undefined)
    setText(next)
  }
  const retry = () => setAttempt((n) => n + 1)
  const ready = state.key === key
  const data = state.data
  const capped = (data?.lines.length ?? 0) >= LIMIT

  return (
    <>
      <Header crumb="Infrastructure" title="Logs" />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        <form onSubmit={search} className="flex flex-wrap items-center gap-2 text-[13px]">
          <input
            type="search"
            name="text"
            aria-label="Search log text"
            placeholder="Search message text, press Enter"
            value={draft}
            maxLength={200}
            onChange={(e) => setDraft(e.target.value)}
            className="min-h-8 w-72 rounded-md border border-bd2 bg-bg px-3"
          />
          <Chips label="Service" options={SERVICES} value={service} onChange={setService} />
          <Chips label="Level" options={LEVELS} value={level} onChange={setLevel} />
          {requestId && (
            <button
              type="button"
              aria-label={`Clear request id filter ${requestId}`}
              onClick={() => setRequest()}
              className="flex items-center gap-1 rounded-full border border-blue bg-bg2 px-2.5 py-0.5 font-mono text-xs"
            >
              req={requestId}
              <X aria-hidden className="size-3" />
            </button>
          )}
        </form>
        {(hint || (rawRequest && !requestId)) && (
          <p role="note" className="text-xs text-er">
            {hint ?? 'Ignored the request id in the address: it may only hold letters, digits, - and _ (up to 64).'}
          </p>
        )}
        {state.error && ready ? (
          <div role="alert" className="flex flex-col items-start gap-2 rounded-lg border border-bd bg-bg p-4 text-[13px]">
            <p className="font-medium">{errorText(state.error)}</p>
            <p className="text-mu">The log lines could not be loaded.</p>
            <Button variant="outline" size="sm" onClick={retry}>
              Retry
            </Button>
          </div>
        ) : !data || !ready ? (
          <p role="status" className="text-[13px] text-mu">
            Loading logs
          </p>
        ) : (
          <>
            <BarsWidget
              title="Log volume"
              sub={capped ? `by level, newest ${data.lines.length} lines only` : 'by level'}
              series={volume(data.lines, range)}
              times={buckets(data.lines, range, data.at, capped)}
            />
            <div className="flex flex-wrap items-start gap-3">
              <Facets lines={data.lines} capped={capped} />
              <Stream lines={data.lines} onRequest={setRequest} />
            </div>
          </>
        )}
      </div>
    </>
  )
}

function Chips<T extends string>({ label, options, value, onChange }: { label: string; options: T[]; value?: T; onChange: (v?: T) => void }) {
  return (
    <div role="group" aria-label={label} className="flex items-center gap-1">
      {[undefined, ...options].map((o) => (
        <button
          key={o ?? 'all'}
          type="button"
          aria-pressed={o === value}
          onClick={() => onChange(o)}
          className="rounded-full border border-bd px-2.5 py-0.5 text-xs text-mu aria-pressed:border-tx aria-pressed:text-tx"
        >
          {o ?? 'all'}
        </button>
      ))}
    </div>
  )
}

/**
 * Bucket starts, oldest first, the last one holding `at`. The first is the partial bucket at the
 * range's start, so every line in range has a bar. When the lines are capped the older part of
 * the range was never fetched, so the axis starts at the oldest returned line, not at zeros.
 */
function buckets(lines: LogLine[], range: Range, at: number, capped: boolean): number[] {
  const step = stepMs(stepFor(range))
  const end = Math.floor(at / step) * step
  let start = Math.floor((at - RANGE_MS[range]) / step) * step
  if (capped) start = Math.max(start, Math.floor(Math.min(...lines.map((l) => Date.parse(l.time ?? '') || end)) / step) * step)
  return Array.from({ length: (end - start) / step + 1 }, (_, i) => start + i * step)
}

/** Line counts per bucket, one series per level present (so a quiet level adds no legend entry). */
function volume(lines: LogLine[], range: Range): Series[] {
  const step = stepMs(stepFor(range))
  const counts = new Map<string, Map<number, number>>()
  for (const { time, level } of lines) {
    const t = time ? Date.parse(time) : NaN
    if (!level || Number.isNaN(t)) continue
    const bucket = Math.floor(t / step) * step
    const row = counts.get(level) ?? new Map<number, number>()
    counts.set(level, row.set(bucket, (row.get(bucket) ?? 0) + 1))
  }
  return LEVELS.filter((l) => counts.has(l)).map((l) => ({
    label: l,
    color: LEVEL_COLOR[l],
    points: [...(counts.get(l) as Map<number, number>)].map(([t, v]) => ({ t, v })),
  }))
}

function Facets({ lines, capped }: { lines: LogLine[]; capped: boolean }) {
  const count = (pick: (l: LogLine) => string | null) => {
    const counts = new Map<string, number>()
    for (const l of lines) {
      const v = pick(l)
      if (v) counts.set(v, (counts.get(v) ?? 0) + 1)
    }
    return [...counts].sort((a, b) => b[1] - a[1])
  }
  const facets: [string, [string, number][]][] = [
    ['Service', count((l) => l.service)],
    ['Level', count((l) => l.level)],
  ]
  return (
    <section aria-label="Facets" className="w-56 flex-none">
    <Card title="Facets" sub={capped ? `newest ${lines.length} lines, more may match` : `${lines.length} returned lines`} className="h-full">
      {facets.map(([title, rows]) => (
        <div key={title} className="mb-2 flex flex-col gap-1 text-xs">
          <span className="font-medium">{title}</span>
          {rows.map(([name, n]) => (
            <div key={name} className="flex justify-between">
              <span className={LEVEL_TONE[name]}>{name}</span>
              <span className="text-mu">{n}</span>
            </div>
          ))}
        </div>
      ))}
    </Card>
    </section>
  )
}

function Stream({ lines, onRequest }: { lines: LogLine[]; onRequest: (id: string) => void }) {
  return (
    <section aria-label="Stream" className="min-w-[320px] flex-[999_1_560px]">
    <Card title="Stream" sub="newest first" flush className="h-full">
      {lines.length === 0 ? (
        <p className="py-6 text-center text-xs text-mu">No log lines match</p>
      ) : (
        <div className="max-h-[560px] overflow-y-auto">
          {lines.map((l, i) => (
            <Row key={i} line={l} onRequest={onRequest} />
          ))}
        </div>
      )}
    </Card>
    </section>
  )
}

function Row({ line, onRequest }: { line: LogLine; onRequest: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const message = line.message ?? ''
  const long = message.length > LONG
  return (
    <div className={cn('grid grid-cols-[100px_48px_56px_1fr] items-baseline gap-2.5 border-t border-bd px-3 py-1.5 font-mono text-xs', ROW_TONE[line.level ?? ''])}>
      <span className="text-mu">{formatClock(line.time)}</span>
      <span className={cn('uppercase', LEVEL_TONE[line.level ?? ''] ?? 'text-mu')}>{line.level}</span>
      <span>{line.service}</span>
      <span className="min-w-0">
        <span className={cn('whitespace-pre-wrap break-words', long && !open && 'line-clamp-2')}>{message}</span>{' '}
        {long && (
          <button type="button" onClick={() => setOpen(!open)} className="text-blue">
            {open ? 'less' : 'more'}
          </button>
        )}
        {line.requestId && (
          <button type="button" onClick={() => onRequest(line.requestId as string)} className="ml-1 text-mu underline-offset-2 hover:underline">
            req={line.requestId}
          </button>
        )}
      </span>
    </div>
  )
}
