import { type ReactNode, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { MetricQuery } from '@/api/metrics'
import { useLive } from '@/data/live'
import { httpErrorRate, httpHits, httpLatency, latest, rate, successRate } from '@/data/presets'
import { type ServiceStatus, useLogs, useStatus } from '@/data/streamHooks'
import { type MetricState, useMetric } from '@/data/useMetric'
import Header from '@/layout/Header'
import { cn } from '@/lib/utils'
import BarsWidget from '@/widgets/BarsWidget'
import Card, { Empty } from '@/widgets/Card'
import Group from '@/widgets/Group'
import LinesWidget from '@/widgets/LinesWidget'
import { type Tone, formatClock, formatCount, formatMs, formatTime } from '@/widgets/scale'
import StreamWidget from '@/widgets/StreamWidget'
import TileWidget from '@/widgets/TileWidget'

type Detail = { query: MetricQuery; format: (v: number) => string }

const perMin = (unit: string) => (v: number) => `${formatCount(v * 60)} ${unit}/min`

/** The detail line of a service tile: its latest sample of one catalogue metric. Services absent here show none. */
const DETAILS: Record<string, Detail> = {
  backend: { query: rate('http.server.duration'), format: perMin('req') },
  bridge: { query: { metric: 'bridge.consumer.lag', agg: 'last' }, format: (v) => `lag ${formatCount(v)}` },
  mongodb: { query: { metric: 'mongo.command.duration', agg: 'p95' }, format: (v) => `p95 ${formatMs(v)}` },
  redpanda: { query: rate('kafka.messages'), format: perMin('msg') },
  minio: { query: { metric: 'minio.objects', agg: 'last' }, format: (v) => `${formatCount(v)} objects` },
  victoriametrics: {
    query: { metric: 'vm.series.active', agg: 'last', filters: { type: 'storage/hour_metric_ids' } },
    format: (v) => `${formatCount(v)} series`,
  },
  'otel-collector': { query: rate('collector.received'), format: perMin('points') },
}

/** restock-cron has no health check: its tile is its last run (`job.run`, one point per run, tagged by result). */
const RESTOCK_RUNS: MetricQuery = { metric: 'job.run', agg: 'last', filters: { job: 'restock', field: 'duration_ms' }, groupBy: ['result'] }

const STATE_TONE: Record<ServiceStatus['state'], Tone> = { UP: 'ok', DOWN: 'error', UNKNOWN: 'warn' }

const SECTIONS = ['Services', 'Right now', 'Problems'] as const

/** The first page: one tile per service, every endpoint together, and the latest errors and warnings. */
export default function Overview() {
  const [tab, setTab] = useState<string>(SECTIONS[0])
  const services = useRef<HTMLDivElement>(null)
  const now = useRef<HTMLDivElement>(null)
  const problems = useRef<HTMLDivElement>(null)
  const onTab = (label: string) => {
    setTab(label)
    const target = label === 'Services' ? services : label === 'Right now' ? now : problems
    target.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
  return (
    <>
      <Header crumb="Console" title="Overview" tabs={[...SECTIONS]} tab={tab} onTab={onTab} />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        <div ref={services}>
          <Services />
        </div>
        <div ref={now}>
          <RightNow />
        </div>
        <div ref={problems}>
          <Problems />
        </div>
      </div>
    </>
  )
}

function Services() {
  const { data, loading } = useStatus()
  return (
    <Group title="services" tag="every container, one status each">
      {loading ? (
        <Skeleton className="col-span-3" />
      ) : (
        data.services.map((s) => <ServiceTile key={s.name} service={s} />)
      )}
      <RestockTile />
    </Group>
  )
}

function ServiceTile({ service }: { service: ServiceStatus }) {
  const detail = DETAILS[service.name]
  return detail ? <MeasuredTile service={service} detail={detail} /> : <StatusTile service={service} />
}

function MeasuredTile({ service, detail }: { service: ServiceStatus; detail: Detail }) {
  const state = useMetric(detail.query)
  // Ungrouped, every detail query is one series: `last` collapses series to their max (bridge lag
  // over flows), rate and p95 to the sum or the quantile over all of them.
  const v = latest(state.series[0])
  return <StatusTile service={service} measured={!state.error && v !== undefined ? detail.format(v) : undefined} />
}

/** A DOWN or UNKNOWN row says why (the probe's detail); an UP row shows what the service is doing right now. */
function StatusTile({ service: { name, state, detail }, measured }: { service: ServiceStatus; measured?: string }) {
  const line = state === 'UP' ? measured : (detail ?? measured)
  return <TileWidget value={state} tone={STATE_TONE[state]} label={line ? `${name}, ${line}` : name} className="col-span-3" />
}

function RestockTile() {
  const { range } = useLive()
  const runs = useMetric(RESTOCK_RUNS)
  if (runs.loading) return <Skeleton className="col-span-3" />
  const last = runs.series
    .flatMap((s) => s.points.map((p) => ({ t: p.t, result: s.label })))
    .reduce<{ t: number; result: string } | undefined>((a, b) => (a && a.t >= b.t ? a : b), undefined)
  // No run in range is not a failure (the cron runs daily), but not a confirmed success either.
  if (!last) return <TileWidget value="NO RUN" tone="warn" label={`restock-cron, no run in the past ${range}`} className="col-span-3" />
  const ok = last.result === 'ok'
  return (
    <TileWidget
      value={ok ? 'OK' : 'FAILED'}
      tone={ok ? 'ok' : 'error'}
      label={`restock-cron, last run ${formatTime(last.t, false)}`}
      className="col-span-3"
    />
  )
}

const percent = (v: number) => `${+v.toFixed(2)}%`

/** The Monitoring pages' four route widgets, over every endpoint (no route or method filter). */
function RightNow() {
  const hits = httpHits()
  const latency = httpLatency()
  const counts = useMetric(hits.queries)
  const timing = useMetric(latency.queries)
  const errors = httpErrorRate().select(counts.series)
  const success = successRate().select(counts.series)
  return (
    <Group title="right now" tag="all endpoints together">
      <Panel state={counts} title="Hits" className="col-span-4">
        <BarsWidget title="Hits" sub="by status class" series={hits.select(counts.series)} times={counts.times} className="col-span-4" />
      </Panel>
      <Panel state={timing} title="Latency" className="col-span-4">
        <LinesWidget title="Latency" sub="p50 / p95 / p99" format={formatMs} series={latency.select(timing.series)} times={timing.times} className="col-span-4" />
      </Panel>
      <Panel state={counts} title="Error rate" className="col-span-2">
        <LinesWidget title="Error rate" area format={percent} series={errors} times={counts.times} className="col-span-2" />
      </Panel>
      <Panel state={counts} title="Success rate" className="col-span-2">
        {success ? (
          <TileWidget value={success.value} tone={success.tone} label="Success rate" spark={errors[0].points.map((p) => 100 - p.v)} className="col-span-2" />
        ) : (
          <Card title="Success rate" className="col-span-2">
            <Empty />
          </Card>
        )}
      </Panel>
    </Group>
  )
}

const PROBLEMS = 50

// Instants differ in fraction digits, so they sort as times, not text; a missing one sorts last.
const instant = (iso: string | null) => (iso ? Date.parse(iso) : 0) || 0

/** Error and warning lines of the backend and the bridge, merged newest first; a request id opens its logs. */
function Problems() {
  const errors = useLogs({ level: 'error', limit: PROBLEMS })
  const warnings = useLogs({ level: 'warn', limit: PROBLEMS })
  const lines = [...errors.data, ...warnings.data]
    .sort((a, b) => instant(b.time) - instant(a.time))
    .slice(0, PROBLEMS)
  const rows = lines.map((l) => ({
    time: formatClock(l.time),
    tag: l.level ?? '-',
    tone: l.level === 'error' ? 'error' : 'warn',
    text: (
      <>
        {l.service && `${l.service}: `}
        {l.message}
        {l.requestId && (
          <>
            {' '}
            <Link to={`/infra/logs?req=${encodeURIComponent(l.requestId)}`} className="text-mu hover:underline">
              req={l.requestId}
            </Link>
          </>
        )}
      </>
    ),
  }))
  const error = errors.error ?? warnings.error
  return (
    <Group title="recent problems" tag="newest first, click to open the request">
      {error ? (
        <Card title="Errors and warnings" className="col-span-12">
          <p className="py-6 text-center text-xs text-er">{error.message}</p>
        </Card>
      ) : errors.loading && warnings.loading ? (
        <Skeleton className="col-span-12" />
      ) : rows.length ? (
        <StreamWidget title="Errors and warnings" sub="across backend and bridge" rows={rows} live className="col-span-12" />
      ) : (
        <Card title="Errors and warnings" sub="across backend and bridge" className="col-span-12">
          <p className="py-6 text-center text-xs text-mu">No errors or warnings</p>
        </Card>
      )}
    </Group>
  )
}

export function Skeleton({ className }: { className: string }) {
  return <div role="status" aria-label="Loading" className={cn('min-h-[150px] animate-pulse rounded-lg border border-bd bg-bg2', className)} />
}

/** A widget's loading skeleton or its error (the message from the API); otherwise the widget. */
export function Panel({ state, title, className, children }: { state: MetricState; title: string; className: string; children: ReactNode }) {
  if (state.error) {
    return (
      <Card title={title} className={className}>
        <p className="py-6 text-center text-xs text-er">{state.error.message}</p>
      </Card>
    )
  }
  if (state.loading) return <Skeleton className={className} />
  return children
}
