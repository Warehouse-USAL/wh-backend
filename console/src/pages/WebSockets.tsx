import type { MetricQuery } from '@/api/metrics'
import { useLive } from '@/data/live'
import { type WsEvent, type WsFeed, useWsEvents } from '@/data/streamHooks'
import { stepFor, useMetric } from '@/data/useMetric'
import Header from '@/layout/Header'
import BarsWidget from '@/widgets/BarsWidget'
import Card, { Empty } from '@/widgets/Card'
import Group from '@/widgets/Group'
import { PALETTE, type Series, formatClock, toneFor } from '@/widgets/scale'
import StreamWidget from '@/widgets/StreamWidget'
import TileWidget from '@/widgets/TileWidget'
import { Panel, Skeleton } from './Overview'

/** The four feeds: `path` is the `feed` tag of the `ws.*` metrics, `slug` names the event buffer. */
const FEEDS: { path: string; slug: WsFeed; desc: string }[] = [
  { path: '/ws/v1/orders', slug: 'orders', desc: 'all order events' },
  { path: '/ws/v1/orders/{userId}', slug: 'user-orders', desc: 'order events for one user' },
  { path: '/ws/v1/vehicles', slug: 'vehicles', desc: 'vehicle state and telemetry' },
  { path: '/ws/v1/stock/alerts', slug: 'stock-alerts', desc: 'low-stock alerts' },
]

/** Monitoring / WebSockets: one group per feed, its sessions and messages, then its event buffer. */
export default function WebSockets() {
  return (
    <>
      <Header crumb="Monitoring" title="WebSockets" tabs={['Feeds']} />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        {FEEDS.map((f) => (
          <FeedGroup key={f.slug} {...f} />
        ))}
      </div>
    </>
  )
}

const pick = (series: Series[], label: string, as: { label: string; color: string }) =>
  series.filter((s) => s.label === label).map((s) => ({ ...s, ...as }))

const total = (series: Series[], label: string) =>
  series.filter((s) => s.label === label).flatMap((s) => s.points).reduce((n, p) => n + p.v, 0)

function FeedGroup({ path, slug, desc }: (typeof FEEDS)[number]) {
  const { range } = useLive()
  const sessionsQuery: MetricQuery = { metric: 'ws.sessions', agg: 'last', filters: { feed: path } }
  const messagesQuery: MetricQuery = { metric: 'ws.messages', agg: 'increase', filters: { feed: path }, groupBy: ['result'] }
  const sessions = useMetric(sessionsQuery)
  // Pushes, disconnects and the health tile share one query (by result).
  const messages = useMetric(messagesQuery)
  const per = `per ${stepFor(range)}`

  const clients = sessions.series.map((s) => ({ ...s, label: 'connected clients', color: PALETTE.blue }))
  const pushed = pick(messages.series, 'pushed', { label: 'pushed', color: PALETTE.blue })
  const disconnects = [
    ...pick(messages.series, 'closed', { label: 'closed', color: PALETTE.gray }),
    ...pick(messages.series, 'rejected', { label: 'rejected', color: PALETTE.red }),
  ]
  // Every session ends closed or is rejected at the handshake; health is the share not rejected.
  const rejected = total(messages.series, 'rejected')
  const ended = rejected + total(messages.series, 'closed')
  const healthy = ended > 0 ? (1 - rejected / ended) * 100 : undefined

  return (
    <Group title={<span className="font-mono">{path}</span>} tag={desc}>
      <Panel state={sessions} title="Clients" className="col-span-3">
        <BarsWidget title="Clients" sub="connected" series={clients} times={sessions.times} className="col-span-3" />
      </Panel>
      <Panel state={messages} title="Messages pushed" className="col-span-3">
        <BarsWidget title="Messages pushed" sub={per} series={pushed} times={messages.times} className="col-span-3" />
      </Panel>
      <Panel state={messages} title="Disconnects" className="col-span-3">
        <BarsWidget title="Disconnects" sub="by reason" series={disconnects} times={messages.times} className="col-span-3" />
      </Panel>
      <Panel state={messages} title="Healthy sessions" className="col-span-3">
        {healthy === undefined ? (
          <Card title="Healthy sessions" className="col-span-3">
            <Empty />
          </Card>
        ) : (
          <TileWidget value={`${+healthy.toFixed(2)}%`} tone={toneFor(healthy)} label="Healthy sessions" className="col-span-3" />
        )}
      </Panel>
      <EventBuffer feed={slug} />
    </Group>
  )
}

const KIND_TONE: Record<WsEvent['kind'], string> = { connect: 'ok', push: 'info', close: 'warn', reject: 'error' }

const TITLE = 'Event buffer'
const SUB = 'live feed of what has come through this feed, last 200 events'

/** The backend's last 200 events of one feed, pushed as they happen; newest first. */
function EventBuffer({ feed }: { feed: WsFeed }) {
  const { data, loading, error } = useWsEvents(feed)
  if (error) {
    return (
      <Card title={TITLE} sub={SUB} className="col-span-12">
        <p className="py-6 text-center text-xs text-er">{error.message}</p>
      </Card>
    )
  }
  if (loading) return <Skeleton className="col-span-12" />
  if (!data.length) {
    return (
      <Card title={TITLE} sub={SUB} className="col-span-12">
        <p className="py-6 text-center text-xs text-mu">No events yet</p>
      </Card>
    )
  }
  const rows = [...data]
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    .map((e) => ({ time: formatClock(e.at), tag: e.kind, tone: KIND_TONE[e.kind], text: e.detail }))
  return <StreamWidget title={TITLE} sub={SUB} rows={rows} live className="col-span-12" />
}
