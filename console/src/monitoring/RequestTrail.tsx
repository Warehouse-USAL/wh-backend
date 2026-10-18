import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { errorText } from '@/api/client'
import { type LogLine, queryLogs, trailFor } from '@/api/logs'
import { useLive } from '@/data/live'
import Card from '@/widgets/Card'
import Group from '@/widgets/Group'
import { formatClock } from '@/widgets/scale'
import StreamWidget from '@/widgets/StreamWidget'

// Creating an order logs nothing in the backend; the bridge logs this line, with the request id,
// when it forwards that order's Kafka message, so the newest such line names the latest order flow.
const ORDER_MARK = 'order published to infra'
const DAY_MS = 86_400_000

type State = { id?: string; lines: LogLine[]; error?: string; ready: boolean }

/** The Orders page's event trail: every log line, across services, of the latest order request. */
export default function RequestTrail() {
  const { tick } = useLive()
  const [state, setState] = useState<State>({ lines: [], ready: false })

  useEffect(() => {
    const controller = new AbortController()
    const now = Date.now()
    const latest = { service: 'bridge', text: ORDER_MARK, from: new Date(now - DAY_MS), to: new Date(now), limit: 1 } as const
    queryLogs(latest, controller.signal)
      .then(async ({ lines }) => {
        const id = lines[0]?.requestId ?? undefined
        return { id, lines: id ? await trailFor(id, controller.signal) : [], ready: true }
      })
      .then(
        (next) => !controller.signal.aborted && setState(next),
        (error: unknown) => !controller.signal.aborted && setState({ lines: [], ready: true, error: errorText(error) }),
      )
    return () => controller.abort()
  }, [tick])

  const { id, lines, error, ready } = state
  return (
    <Group title="Event trail" tag="why it happened: one request followed end to end" right={id && <Link to={`/infra/logs?req=${id}`}>Open in Logs</Link>}>
      {error || !id ? (
        <Card title="Latest order request" className="col-span-12">
          <p className="py-6 text-center text-xs text-mu">{error ?? (ready ? 'No order request in the last 24 hours' : 'Loading the trail')}</p>
        </Card>
      ) : (
        <StreamWidget
          title={`Request ${id}`}
          sub="latest order, oldest line first"
          className="col-span-12"
          rows={lines.map((l) => ({
            time: formatClock(l.time),
            tag: l.service ?? 'log',
            tone: l.level === 'warn' || l.level === 'error' ? l.level : 'info',
            text: l.message,
          }))}
        />
      )}
    </Group>
  )
}
