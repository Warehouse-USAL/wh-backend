import Chart, { type ChartProps } from './Chart'
import { formatCount, niceMax, plotData, tooltip } from './scale'

const num = (v: number) => +v.toFixed(2)

/** Stacked bars, one per timestamp; series are stacked in order, first at the bottom. */
export default function BarsWidget(props: ChartProps) {
  const { series } = props
  const data = plotData(series, props.times)
  const totals = data.times.map((_, i) => data.values.reduce((sum, row) => sum + (row[i] ?? 0), 0))
  const max = niceMax(Math.max(0, ...totals) * 1.05)
  const slot = 300 / Math.max(data.times.length, 1)
  const width = Math.min(slot * 0.64, 16)

  return (
    <Chart {...props} data={data} max={max} format={formatCount}>
      {data.times.map((t, i) => {
        let base = 100
        return series.map((s, j) => {
          const v = data.values[j][i]
          if (!v) return null
          const h = (v / max) * 100
          base -= h
          return (
            <rect key={`${t}-${j}`} x={num((i + 0.5) * slot - width / 2)} y={num(base)} width={num(width)} height={num(h)} fill={s.color}>
              <title>{tooltip(t, [`${s.label} ${formatCount(v)}`])}</title>
            </rect>
          )
        })
      })}
    </Chart>
  )
}
