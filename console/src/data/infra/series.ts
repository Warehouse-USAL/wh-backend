import { ratioSeries, sumSeries } from '@/data/presets'
import { PALETTE, type Point, type Series, type Tone, toneFor } from '@/widgets/scale'
import type { TileValue } from './spec'
import { percent } from './format'

/** `[answered label, shown label?, colour?]`: keeps those series, in this order, renamed. */
export type Pick = [from: string, to?: string, color?: string]

export const pick =
  (...wanted: Pick[]) =>
  (series: Series[]): Series[] =>
    wanted.flatMap(([from, to = from, color]) => series.filter((s) => s.label === from).map((s) => ({ ...s, label: to, color: color ?? s.color })))

export const mapPoints = (series: Series[], f: (v: number) => number): Series[] =>
  series.map((s) => ({ ...s, points: s.points.map((p) => ({ t: p.t, v: f(p.v) })) }))

/** Multiplies every value (per second to per minute, seconds to ms, bytes to MB). */
export const scale = (k: number) => (series: Series[]) => mapPoints(series, (v) => v * k)

export const total = (series: Series[]) => series.reduce((n, s) => n + s.points.reduce((m, p) => m + p.v, 0), 0)

export const peak = (s: Series) => Math.max(...s.points.map((p) => p.v))

/** The newest point of all the series, if any. */
export function newest(series: Series[]): (Point & { label: string }) | undefined {
  let best: (Point & { label: string }) | undefined
  for (const s of series) for (const p of s.points) if (!best || p.t > best.t) best = { ...p, label: s.label }
  return best
}

export const lastValue = (series: Series[], label?: string) => series.find((s) => label === undefined || s.label === label)?.points.at(-1)?.v

/**
 * Share of `bad` labels among the `counted` ones at each timestamp, in percent. A bucket with
 * counted traffic and no bad series is 0 %; a bucket without traffic has no point.
 */
export const badShare =
  (bad: string[], counted?: string[]) =>
  (series: Series[]): Series[] => {
    const all = series.filter((s) => !counted || counted.includes(s.label))
    const share = ratioSeries(
      sumSeries(all.filter((s) => bad.includes(s.label)), { label: 'bad', color: PALETTE.red }),
      sumSeries(all, { label: 'all', color: PALETTE.gray }),
      { label: 'errors %', color: PALETTE.red, missingAs: 0 },
    )
    return mapPoints([share], (v) => v * 100)
  }

/** Share of good outcomes over the whole range, for a tile; undefined without traffic. */
export const goodTile =
  (bad: string[], counted?: string[]) =>
  (series: Series[]): TileValue | undefined => {
    const all = series.filter((s) => !counted || counted.includes(s.label))
    const sum = total(all)
    if (sum <= 0) return undefined
    const pct = (1 - total(all.filter((s) => bad.includes(s.label))) / sum) * 100
    return { value: percent(pct), tone: toneFor(pct), spark: badShare(bad, counted)(series)[0].points.map((p) => 100 - p.v) }
  }

/** A count where any is worth a look (refused points, rejected rows, retries). */
export const countTone = (v: number): Tone => (v > 0 ? 'warn' : 'ok')
