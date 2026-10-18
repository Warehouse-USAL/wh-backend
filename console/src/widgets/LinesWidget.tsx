import Chart, { type ChartProps } from './Chart'
import { formatCount, niceMax, plotData, tooltip } from './scale'

type Props = ChartProps & {
  format?: (v: number) => string
  /** Fills the area under each line. */
  area?: boolean
}

const num = (v: number) => +v.toFixed(2)

/** One line per series on a shared time axis; a missing bucket breaks the line instead of dropping to zero. */
export default function LinesWidget({ format = formatCount, area, ...props }: Props) {
  const { series } = props
  const data = plotData(series, props.times)
  const n = data.times.length
  const max = niceMax(Math.max(0, ...data.values.flat().map((v) => v ?? 0)) * 1.05)
  const x = (i: number) => (n === 1 ? 150 : (i / (n - 1)) * 300)

  return (
    <Chart {...props} data={data} max={max} format={format}>
      {series.map((s, j) => {
        const runs: [number, number][][] = []
        data.values[j].forEach((v, i) => {
          if (v === undefined) runs.push([])
          else {
            if (!runs.length) runs.push([])
            runs[runs.length - 1].push([num(x(i)), num(100 - (v / max) * 100)])
          }
        })
        return runs
          .filter((r) => r.length)
          .map((r, k) => {
            const key = `${j}-${k}`
            if (r.length === 1) {
              return <path key={key} d={`M${r[0][0]},${r[0][1]}h0`} stroke={s.color} strokeWidth={5} strokeLinecap="round" vectorEffect="non-scaling-stroke" />
            }
            const pts = r.map((p) => p.join(',')).join(' ')
            return (
              <g key={key}>
                {area && <path d={`M${r[0][0]},100L${r.map((p) => p.join(',')).join('L')}L${r[r.length - 1][0]},100Z`} fill={s.color} fillOpacity={0.12} />}
                <polyline points={pts} fill="none" stroke={s.color} strokeWidth={1.6} vectorEffect="non-scaling-stroke" />
              </g>
            )
          })
      })}
      {data.times.map((t, i) => (
        <rect key={t} x={num(x(i) - 150 / Math.max(n, 1))} y={0} width={num(300 / n)} height={100} fill="transparent">
          <title>
            {tooltip(
              t,
              series.flatMap((s, j) => (data.values[j][i] === undefined ? [] : [`${s.label} ${format(data.values[j][i] as number)}`])),
            )}
          </title>
        </rect>
      ))}
    </Chart>
  )
}
