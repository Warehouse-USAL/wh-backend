import type { ReactNode } from 'react'
import Card, { Empty } from './Card'
import { type PlotData, type Series, summary, timeLabels } from './scale'

export type ChartProps = {
  title: string
  sub?: string
  series: Series[]
  className?: string
}

type Props = ChartProps & {
  data: PlotData
  max: number
  format: (v: number) => string
  /** SVG content drawn in a 300 x 100 box (y grows downwards). */
  children: ReactNode
}

const GRID_LINES = [25, 50, 75]

/** Shared frame of the time-series widgets: plot box, y-axis labels, x-axis times and legend. */
export default function Chart({ title, sub, series, className, data, max, format, children }: Props) {
  const empty = data.times.length === 0
  return (
    <Card title={title} sub={sub} className={className}>
      {empty ? (
        <Empty />
      ) : (
        <>
          <div className="relative h-[104px]">
            <svg
              viewBox="0 0 300 100"
              preserveAspectRatio="none"
              role="img"
              aria-label={summary({ title, sub, series }, data, format)}
              className="absolute inset-0 block h-full w-full"
            >
              <path
                d={GRID_LINES.map((y) => `M0,${y}H300`).join('')}
                className="stroke-bd"
                fill="none"
                strokeWidth={1}
                vectorEffect="non-scaling-stroke"
              />
              {children}
            </svg>
            <span className="absolute top-0 right-0 bg-bg pl-[3px] text-[10px] text-mu">{format(max)}</span>
            <span className="absolute top-[46%] right-0 bg-bg pl-[3px] text-[10px] text-mu">{format(max / 2)}</span>
          </div>
          <div className="mt-[3px] flex justify-between text-[10px] text-mu">
            {timeLabels(data.times).map((l, i) => (
              <span key={i}>{l}</span>
            ))}
          </div>
          <div className="mt-1.5 flex flex-wrap gap-3 text-[11px] text-mu">
            {series.map((s) => (
              <span key={s.label}>
                <i className="mr-[5px] inline-block size-2 rounded-xs" style={{ background: s.color }} />
                {s.label}
              </span>
            ))}
          </div>
        </>
      )}
    </Card>
  )
}
