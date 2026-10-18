import Card, { Empty } from './Card'

type Props = {
  title: string
  sub?: string
  rows: { name: string; value: number }[]
  color: string
  format: (v: number) => string
  className?: string
}

/** Ranked rows, each with a tinted bar proportional to the largest value. */
export default function TopListWidget({ title, sub, rows, color, format, className }: Props) {
  const valid = rows.filter((r) => Number.isFinite(r.value) && r.value >= 0)
  const max = Math.max(...valid.map((r) => r.value)) || 1
  return (
    <Card title={title} sub={sub} className={className}>
      {valid.length === 0 ? (
        <Empty />
      ) : (
        <div className="flex flex-col gap-1 pt-1">
          {valid.map((r) => (
            <div key={r.name} className="relative flex justify-between gap-2 overflow-hidden rounded-sm bg-bg2 px-2 py-[5px]">
              <i className="absolute inset-y-0 left-0 opacity-[.16]" style={{ width: `${Math.round(Math.max((r.value / max) * 100, 1.5) * 10) / 10}%`, background: color }} />
              <span className="relative font-mono text-xs">{r.name}</span>
              <span className="relative">{format(r.value)}</span>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}
