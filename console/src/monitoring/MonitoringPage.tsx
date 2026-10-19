import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { errorText } from '@/api/client'
import { Button } from '@/components/ui/button'
import { useLive } from '@/data/live'
import { httpErrorRate, httpHits, httpLatency, successRate } from '@/data/presets'
import { type MetricState, RANGE_MS, useMetric } from '@/data/useMetric'
import Header from '@/layout/Header'
import { cn } from '@/lib/utils'
import BarsWidget from '@/widgets/BarsWidget'
import Card, { Empty } from '@/widgets/Card'
import Group from '@/widgets/Group'
import LinesWidget from '@/widgets/LinesWidget'
import { PALETTE, type Series, formatCount, formatMs } from '@/widgets/scale'
import TileWidget from '@/widgets/TileWidget'
import TopListWidget from '@/widgets/TopListWidget'
import { useNearView } from '@/widgets/useNearView'
import { type Domain, type RouteInfo, loadRoutes } from './routes'

/** A tab jumps to a section of the page (`section`, the `extra` slot, else the top) or opens `to`. */
type Tab = { label: string; section?: string; extra?: true; to?: string }

const ENDPOINTS: Tab = { label: 'Endpoints' }
const collection = (name: string): Tab => ({ label: `Collection ${name}`, to: '/infra/mongodb' })
const sections = (...names: [string, string][]) => names.map(([label, section]) => ({ label, section }))

const TABS: Record<Domain, Tab[]> = {
  'Users and auth': [ENDPOINTS, collection('users')],
  Products: [ENDPOINTS, collection('products')],
  Orders: [ENDPOINTS, { label: 'Event trail', extra: true }, collection('orders')],
  Vehicles: [ENDPOINTS, { label: 'Live feed', to: '/monitoring/websockets' }, collection('vehicles')],
  Warehouse: sections(['Zones', 'Zones'], ['Lines', 'Lines'], ['Positions', 'Positions']),
  Restock: sections(['Orders', 'Restock orders'], ['Receptions', 'Receptions']),
  Files: [ENDPOINTS, { label: 'Bucket (MinIO)', to: '/infra/minio' }],
  'Metrics and query': sections(['Metrics', 'Metrics'], ['Generic query', 'Generic query (dashboard only)']),
  'Infra callbacks': [ENDPOINTS, { label: 'Bridge exchanges', to: '/infra/bridge' }],
}

const TOP = 'top'
const EXTRA = 'extra'
const anchorOf = (tab: Tab) => tab.section ?? (tab.extra ? EXTRA : TOP)

type Props = {
  domain: Domain
  /** Rendered after the endpoint groups (the Orders page's event trail). */
  extra?: ReactNode
}

/** A Monitoring page: ranked lists over the domain's routes, then one group per route from the API's spec. */
export default function MonitoringPage({ domain, extra }: Props) {
  const navigate = useNavigate()
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<{ routes?: RouteInfo[]; error?: string }>({})
  const tabs = TABS[domain].filter((t) => !t.extra || extra)
  const [tab, setTab] = useState(tabs[0].label)
  const anchors = useRef(new Map<string, HTMLElement>())
  const anchor = useCallback((key: string) => (el: HTMLElement | null) => void (el && anchors.current.set(key, el)), [])

  useEffect(() => {
    let current = true
    loadRoutes().then(
      (all) => current && setLoaded({ routes: all }),
      (error: unknown) => current && setLoaded({ error: errorText(error) }),
    )
    return () => {
      current = false
    }
  }, [attempt])

  const onTab = (label: string) => {
    const target = tabs.find((t) => t.label === label) as Tab
    if (target.to) return void navigate(target.to)
    setTab(label)
    anchors.current.get(anchorOf(target))?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
  const retry = () => {
    setLoaded({})
    setAttempt((n) => n + 1)
  }

  const routes = loaded.routes?.filter((r) => r.domain === domain)
  const bySection = new Map<string, RouteInfo[]>()
  for (const r of routes ?? []) bySection.set(r.section, [...(bySection.get(r.section) ?? []), r])
  return (
    <>
      <Header crumb="Monitoring" title={domain} tabs={tabs.map((t) => t.label)} tab={tab} onTab={onTab} />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        {loaded.error ? (
          <div role="alert" className="flex flex-col items-start gap-2 rounded-lg border border-bd bg-bg p-4 text-[13px]">
            <p className="font-medium">{loaded.error}</p>
            <p className="text-mu">The endpoint groups come from the backend&apos;s OpenAPI document, which could not be loaded.</p>
            <Button variant="outline" size="sm" onClick={retry}>
              Retry
            </Button>
          </div>
        ) : !routes ? (
          <p role="status" className="text-[13px] text-mu">
            Loading routes
          </p>
        ) : (
          <>
            <div ref={anchor(TOP)}>
              <Overview routes={routes} />
            </div>
            {[...bySection].map(([section, list]) => (
              <div key={section} className="flex flex-col gap-4">
                <h2 ref={anchor(section)} className="mt-3 flex items-baseline gap-2.5 border-t border-bd pt-3.5 text-base font-semibold tracking-[-0.01em]">
                  {section}
                  <span className="text-xs font-normal text-mu">
                    {list.length} endpoint{list.length === 1 ? '' : 's'}
                  </span>
                </h2>
                {list.map((r) => (
                  <LazyGroup key={`${r.method} ${r.path}`} route={r} />
                ))}
              </div>
            ))}
            {extra && <div ref={anchor(EXTRA)}>{extra}</div>}
          </>
        )}
      </div>
    </>
  )
}

const HTTP = 'http.server.duration'
const BY_ROUTE = ['method', 'route']
const TOP_N = 8

const sum = (s: Series) => s.points.reduce((n, p) => n + p.v, 0)
const peak = (s: Series) => (s.points.length ? Math.max(...s.points.map((p) => p.v)) : 0)

/** Series are labelled `METHOD /path`; only this page's routes rank (never UNMATCHED or another page's), and a route with nothing to rank (zero) is left out. */
function ranked(series: Series[], routes: RouteInfo[], value: (s: Series) => number) {
  const keys = new Set(routes.map((r) => `${r.method} ${r.path}`))
  return series
    .filter((s) => keys.has(s.label))
    .map((s) => ({ name: s.label, value: value(s) }))
    .filter((r) => r.value > 0)
    .sort((a, b) => b.value - a.value)
    .slice(0, TOP_N)
}

function Overview({ routes }: { routes: RouteInfo[] }) {
  const { range } = useLive()
  const requests = useMetric({ metric: HTTP, agg: 'increase', groupBy: BY_ROUTE })
  const latency = useMetric({ metric: HTTP, agg: 'p95', groupBy: BY_ROUTE })
  const errors = useMetric({ metric: HTTP, agg: 'increase', filters: { status_class: '5xx' }, groupBy: BY_ROUTE })
  const minutes = RANGE_MS[range] / 60_000
  const lists = [
    { title: 'Requests', sub: 'per min', state: requests, value: (s: Series) => sum(s) / minutes, color: PALETTE.blue, format: formatCount },
    { title: 'Latency', sub: 'p95, slowest first', state: latency, value: peak, color: PALETTE.amber, format: formatMs },
    { title: 'Errors', sub: `5xx, last ${range}`, state: errors, value: sum, color: PALETTE.red, format: formatCount },
  ]
  return (
    <Group title="Overview" tag="every endpoint on this page">
      {lists.map(({ title, sub, state, value, color, format }) => (
        <Panel key={title} state={state} title={title} className="col-span-4">
          <TopListWidget title={title} sub={sub} rows={ranked(state.series, routes, value)} color={color} format={format} className="col-span-4" />
        </Panel>
      ))}
    </Group>
  )
}

const METHOD_TONE: Record<string, string> = { POST: 'text-blue', PUT: 'text-wn', PATCH: 'text-wn', DELETE: 'text-er' }

/**
 * A route's group. Its widgets mount only while it is in or near the scrolled view, so a page of
 * 40 routes subscribes to the few on screen; away from it, the group keeps its last height so nothing jumps.
 * Without IntersectionObserver every group renders.
 */
function LazyGroup({ route }: { route: RouteInfo }) {
  const { ref, near, minHeight } = useNearView<HTMLDivElement>()

  const title = (
    <>
      <span className={cn('rounded-full border border-bd bg-bg2 px-2 font-mono text-[11px] font-medium text-mu', METHOD_TONE[route.method])}>
        {route.method}
      </span>{' '}
      <span className="font-mono text-xs">{route.path}</span>
    </>
  )
  const auth = <span className="rounded-full border border-bd px-2 text-[11px] text-mu">{route.auth}</span>
  return (
    <div ref={ref} style={{ minHeight }}>
      <Group title={title} tag={route.summary} right={auth}>
        {near ? <RouteWidgets route={route} /> : <div aria-hidden="true" className="col-span-12 h-[190px]" />}
      </Group>
    </div>
  )
}

const percent = (v: number) => `${+v.toFixed(2)}%`

function RouteWidgets({ route: { path, method } }: { route: RouteInfo }) {
  const hits = httpHits(path, method)
  const latency = httpLatency(path, method)
  const errorRate = httpErrorRate(path, method)
  // Hits, error rate and success share one query (by status class).
  const counts = useMetric(hits.queries)
  const timing = useMetric(latency.queries)
  const errors = errorRate.select(counts.series)
  const success = successRate(path, method).select(counts.series)
  return (
    <>
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
    </>
  )
}

/** A widget's loading skeleton or its error (the message from the API); otherwise the widget. */
function Panel({ state, title, className, children }: { state: MetricState; title: string; className: string; children: ReactNode }) {
  if (state.error) {
    return (
      <Card title={title} className={className}>
        <p className="py-6 text-center text-xs text-er">{state.error.message}</p>
      </Card>
    )
  }
  if (state.loading) return <div role="status" aria-label="Loading" className={cn('min-h-[150px] animate-pulse rounded-lg border border-bd bg-bg2', className)} />
  return children
}
