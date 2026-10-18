import { type ReactNode, useEffect, useRef, useState } from 'react'
import type { ApiError } from '@/api/client'
import {
  type ChartSpec,
  type Ctx,
  type GroupSpec,
  type LogStreamSpec,
  type MetricStreamSpec,
  type PageSpec,
  type Span,
  type StatusSpec,
  type Text,
  type TileSpec,
  type ToplistSpec,
  type WidgetSpec,
} from '@/data/infra/spec'
import { formatUptime } from '@/data/infra/format'
import { useLive } from '@/data/live'
import { type ServiceState, useLogs, useStatus } from '@/data/streamHooks'
import { useMetric } from '@/data/useMetric'
import Header from '@/layout/Header'
import { cn } from '@/lib/utils'
import BarsWidget from '@/widgets/BarsWidget'
import Card from '@/widgets/Card'
import Group from '@/widgets/Group'
import LinesWidget from '@/widgets/LinesWidget'
import type { Series, Tone } from '@/widgets/scale'
import StreamWidget from '@/widgets/StreamWidget'
import TileWidget from '@/widgets/TileWidget'
import TopListWidget from '@/widgets/TopListWidget'
import { useNearView } from '@/widgets/useNearView'

const SPAN: Record<Span, string> = { 2: 'col-span-2', 3: 'col-span-3', 4: 'col-span-4', 6: 'col-span-6', 8: 'col-span-8', 12: 'col-span-12' }
const NO_DATA = 'No data in this range'

/**
 * A UI clock for what ages on screen (sample freshness, the next cron run). It only re-renders:
 * the data itself is pushed through `useMetric` and the stream hooks, nothing here fetches.
 */
const CLOCK_MS = 5000
function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), CLOCK_MS)
    return () => clearInterval(id)
  }, [])
  return now
}

/** Renders any page spec: header, then each group lazily (only groups near the view subscribe). */
export default function PresetPage({ spec }: { spec: PageSpec }) {
  const [tab, setTab] = useState(spec.tabs[0])
  const anchors = useRef(new Map<string, HTMLElement>())
  const { range } = useLive()
  const ctx: Ctx = { range, now: useNow() }

  const onTab = (label: string) => {
    setTab(label)
    anchors.current.get(label)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }
  const anchor = (group: GroupSpec) => (el: HTMLElement | null) => void (el && group.tab && anchors.current.set(group.tab, el))

  return (
    <>
      <Header crumb={spec.crumb} title={spec.title} tabs={spec.tabs} tab={tab} onTab={onTab} chips={spec.chips} />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        {spec.groups.map((group) => (
          <LazyGroup key={group.title} group={group} ctx={ctx} empty={spec.empty ?? NO_DATA} anchor={anchor(group)} />
        ))}
        {spec.note && <p className="rounded-lg border border-dashed border-bd2 px-3.5 py-2 text-xs text-mu">{spec.note}</p>}
      </div>
    </>
  )
}

type Shared = { ctx: Ctx; empty: string }

function LazyGroup({ group, anchor, ...shared }: Shared & { group: GroupSpec; anchor: (el: HTMLElement | null) => void }) {
  const { ref, near, minHeight } = useNearView<HTMLDivElement>()
  const setRef = (el: HTMLDivElement | null) => {
    ref.current = el
    anchor(el)
  }
  const title = group.mono ? <span className="font-mono text-xs">{group.title}</span> : group.title
  const right = group.right && <span className="rounded-full border border-bd px-2 text-[11px] text-mu">{group.right}</span>
  return (
    <div ref={setRef} style={{ minHeight }}>
      <Group title={title} tag={group.tag} right={right}>
        {near ? group.widgets.map((w, i) => <Widget key={`${w.kind}-${w.title}-${i}`} spec={w} {...shared} />) : <div aria-hidden="true" className="col-span-12 h-[190px]" />}
      </Group>
    </div>
  )
}

function Widget({ spec, ...shared }: Shared & { spec: WidgetSpec }) {
  switch (spec.kind) {
    case 'bars':
    case 'lines':
      return <ChartPanel spec={spec} {...shared} />
    case 'tile':
      return spec.query.length ? <MetricTile spec={spec} {...shared} /> : <TileView spec={spec} value={spec.value([], shared.ctx)} {...shared} />
    case 'toplist':
      return <ToplistPanel spec={spec} {...shared} />
    case 'stream':
      return spec.source === 'metrics' ? <MetricStream spec={spec} {...shared} /> : <LogStream spec={spec} {...shared} />
    case 'status':
      return <StatusTile spec={spec} {...shared} />
  }
}

const text = (t: Text | undefined, ctx: Ctx, series: Series[]) => (typeof t === 'function' ? t(ctx, series) : t)
const hasPoints = (series: Series[]) => series.some((s) => s.points.length > 0)

type Loadable = { loading: boolean; error?: ApiError }

/** A widget's loading skeleton or its error (the message from the API); otherwise the widget. */
function Panel({ state, title, span, children }: { state: Loadable; title: string; span: Span; children: ReactNode }) {
  if (state.error) {
    return (
      <Card title={title} className={SPAN[span]}>
        <p className="py-6 text-center text-xs text-er">{state.error.message}</p>
      </Card>
    )
  }
  if (state.loading) return <div role="status" aria-label="Loading" className={cn('min-h-[150px] animate-pulse rounded-lg border border-bd bg-bg2', SPAN[span])} />
  return children
}

function EmptyCard({ title, sub, span, message }: { title: string; sub?: string; span: Span; message: string }) {
  return (
    <Card title={title} sub={sub} className={SPAN[span]}>
      <p className="py-6 text-center text-xs text-mu">{message}</p>
    </Card>
  )
}

function ChartPanel({ spec, ctx, empty }: Shared & { spec: ChartSpec }) {
  const state = useMetric(spec.query)
  const series = spec.select ? spec.select(state.series, ctx) : state.series
  const sub = text(spec.sub, ctx, state.series)
  const props = { title: spec.title, sub, series, times: state.times, className: SPAN[spec.span] }
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      {!hasPoints(series) ? (
        <EmptyCard title={spec.title} sub={sub} span={spec.span} message={empty} />
      ) : spec.kind === 'bars' ? (
        <BarsWidget {...props} />
      ) : (
        <LinesWidget {...props} format={spec.format} area={spec.area} />
      )}
    </Panel>
  )
}

function MetricTile({ spec, ...shared }: Shared & { spec: TileSpec }) {
  const state = useMetric(spec.query)
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      <TileView spec={spec} value={spec.value(state.series, shared.ctx)} {...shared} />
    </Panel>
  )
}

function TileView({ spec, value, ctx, empty }: Shared & { spec: TileSpec | StatusSpec; value: ReturnType<TileSpec['value']> }) {
  if (!value) return <EmptyCard title={spec.title} sub={text(spec.sub, ctx, [])} span={spec.span} message={empty} />
  return <TileWidget value={value.value} tone={value.tone} label={value.label ?? spec.title} spark={value.spark} className={SPAN[spec.span]} />
}

function ToplistPanel({ spec, ctx, empty }: Shared & { spec: ToplistSpec }) {
  const state = useMetric(spec.query)
  const rows = spec.rows(state.series, ctx)
  const sub = text(spec.sub, ctx, state.series)
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      {rows.length ? (
        <TopListWidget title={spec.title} sub={sub} rows={rows} color={spec.color} format={spec.format} className={SPAN[spec.span]} />
      ) : (
        <EmptyCard title={spec.title} sub={sub} span={spec.span} message={empty} />
      )}
    </Panel>
  )
}

function MetricStream({ spec, ctx, empty }: Shared & { spec: MetricStreamSpec }) {
  const state = useMetric(spec.query)
  const rows = spec.rows(state.series, ctx)
  const sub = text(spec.sub, ctx, state.series)
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      {rows.length ? <StreamWidget title={spec.title} sub={sub} rows={rows} className={SPAN[spec.span]} /> : <EmptyCard title={spec.title} sub={sub} span={spec.span} message={empty} />}
    </Panel>
  )
}

function LogStream({ spec, ctx, empty }: Shared & { spec: LogStreamSpec }) {
  const state = useLogs(spec.query)
  const rows = spec.rows(state.data)
  const sub = text(spec.sub, ctx, [])
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      {rows.length ? <StreamWidget title={spec.title} sub={sub} rows={rows} live className={SPAN[spec.span]} /> : <EmptyCard title={spec.title} sub={sub} span={spec.span} message={empty} />}
    </Panel>
  )
}

const STATE_TONE: Record<ServiceState, Tone> = { UP: 'ok', DOWN: 'error', UNKNOWN: 'warn' }
const NOT_REPORTED = 'Not reported by the status check'

function StatusTile({ spec, ...shared }: Shared & { spec: StatusSpec }) {
  const state = useStatus()
  const service = state.data.services.find((s) => s.name === spec.query.service)
  const uptime = spec.query.show === 'uptime'
  const value =
    service &&
    (uptime
      ? service.uptimeMs === null
        ? undefined
        : { value: formatUptime(service.uptimeMs), tone: STATE_TONE[service.state] }
      : { value: service.state, tone: STATE_TONE[service.state], label: service.detail ? `${spec.title}, ${service.detail}` : spec.title })
  return (
    <Panel state={state} title={spec.title} span={spec.span}>
      <TileView spec={spec} value={value} {...shared} empty={NOT_REPORTED} />
    </Panel>
  )
}
