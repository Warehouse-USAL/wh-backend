import type { ReactNode } from 'react'
import Card, { Empty } from './Card'

type Row = { time: string; tag: string; tone?: string; text: ReactNode }
type Props = { title: string; sub?: string; rows: Row[]; live?: boolean; className?: string }

const TONE: Record<string, string> = { ok: 'text-ok', warn: 'text-wn', error: 'text-er', info: 'text-blue' }

/** Newest-first event rows: time, tone-coloured tag, free text. Scrolls past its max height. */
export default function StreamWidget({ title, sub, rows, live, className }: Props) {
  return (
    <Card
      title={title}
      sub={sub}
      flush
      className={className}
      right={
        live && (
          <span className="flex items-center gap-1.5 font-medium text-ok">
            <span className="size-[7px] animate-pulse rounded-full bg-current" />
            Live
          </span>
        )
      }
    >
      {rows.length === 0 ? (
        <Empty />
      ) : (
        <div className="max-h-80 overflow-y-auto">
          {rows.map((r, i) => (
            <div key={i} className="grid grid-cols-[100px_64px_1fr] items-baseline gap-2.5 border-t border-bd px-3 py-2">
              <span className="font-mono text-xs text-mu">{r.time}</span>
              <span className={`inline-block w-fit rounded-full border border-bd bg-bg2 px-2 font-mono text-[11px] font-medium ${TONE[r.tone ?? ''] ?? 'text-mu'}`}>{r.tag}</span>
              <span>{r.text}</span>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}
