import { useMemo } from 'react'
import { Link } from 'react-router'
import { errorText } from '@/api/client'
import { type LogLine, MAX_LIMIT } from '@/api/logs'
import { useLogsIn } from '@/data/stream/hooks'
import Card from '@/widgets/Card'
import Group from '@/widgets/Group'
import { formatClock } from '@/widgets/scale'
import StreamWidget from '@/widgets/StreamWidget'

// The bridge logs this line, with the request id, when it forwards an order's Kafka message, so the
// newest such line names the latest order flow.
const LATEST = { service: 'bridge', text: 'order published to infra', limit: 1 } as const
// Backend trail lines start with the component that wrote them; that word becomes the row's tag.
const COMPONENT = /^(http|kafka|mongo|callback) (.*)$/s

function rowOf(l: LogLine) {
  const part = l.service === 'backend' ? COMPONENT.exec(l.message ?? '') : null
  return {
    time: formatClock(l.time),
    tag: part?.[1] ?? l.service ?? 'log',
    tone: l.level === 'warn' || l.level === 'error' ? l.level : 'info',
    text: part?.[2] ?? l.message,
  }
}

/**
 * The Orders page's event trail: every log line, across services, of the latest order request.
 * Two pushed log subscriptions over the last 24 hours: the newest order mark (a newer order moves
 * the trail to it) and that request's lines, which arrive as each service logs them.
 */
export default function RequestTrail() {
  const latest = useLogsIn(LATEST, '24h')
  const id = latest.data[0]?.requestId ?? undefined
  const trail = useLogsIn(id ? { requestId: id, limit: MAX_LIMIT } : null, '24h')
  const lines = useMemo(() => [...trail.data].reverse(), [trail.data])
  const error = latest.error ?? trail.error

  return (
    <Group title="Event trail" tag="why it happened: one request followed end to end" right={id && <Link to={`/infra/logs?req=${id}`}>Open in Logs</Link>}>
      {error || !id || trail.loading ? (
        <Card title="Latest order request" className="col-span-12">
          <p className="py-6 text-center text-xs text-mu">
            {error ? errorText(error) : latest.loading || id ? 'Loading the trail' : 'No order request in the last 24 hours'}
          </p>
        </Card>
      ) : (
        <StreamWidget title={`Request ${id}`} sub="latest order, oldest line first" className="col-span-12" rows={lines.map(rowOf)} />
      )}
    </Group>
  )
}
