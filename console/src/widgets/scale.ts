export type Point = { t: number; v: number }
/** `t` is epoch milliseconds. */
export type Series = { label: string; color: string; points: Point[] }
export type Tone = 'ok' | 'warn' | 'error'

export const PALETTE = { blue: '#0070f3', amber: '#f5a623', red: '#e5484d', gray: '#a1a1a1' }

/** Rounds `v` up to 1, 2 or 5 times a power of ten (axis maximum). */
export function niceMax(v: number): number {
  if (!Number.isFinite(v) || v <= 0) return 1
  const e = 10 ** Math.floor(Math.log10(v))
  const m = [1, 2, 5, 10].find((x) => v <= x * e) as number
  return Number((m * e).toPrecision(12))
}

const trim = (v: number, digits: number) => String(+v.toFixed(digits))

export function formatCount(v: number): string {
  if (v >= 999_950) return `${trim(v / 1e6, 1)}M`
  if (v >= 1000) return `${trim(v / 1e3, 1)}k`
  return trim(v, 2)
}

export function formatMs(v: number): string {
  return v >= 1000 ? `${trim(v / 1000, 2)} s` : `${trim(v, 1)} ms`
}

export function toneFor(pct: number): Tone {
  return pct >= 98 ? 'ok' : pct >= 95 ? 'warn' : 'error'
}

export type PlotData = { times: number[]; values: (number | undefined)[][] }

/**
 * Aligns series on `grid` when given (the query window's buckets, so a bucket missing from every
 * series still takes its slot), else on the union of their timestamps. Points off the grid,
 * negative or non-finite are ignored.
 */
export function plotData(series: Series[], grid?: number[]): PlotData {
  const maps = series.map(
    (s) => new Map(s.points.filter((p) => Number.isFinite(p.v) && p.v >= 0 && Number.isFinite(p.t)).map((p) => [p.t, p.v])),
  )
  const times = grid ?? [...new Set(maps.flatMap((m) => [...m.keys()]))].sort((a, b) => a - b)
  return { times, values: maps.map((m) => times.map((t) => m.get(t))) }
}

const pad = (n: number) => String(n).padStart(2, '0')

export function formatTime(t: number, seconds: boolean): string {
  const d = new Date(t)
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return seconds ? `${hm}:${pad(d.getSeconds())}` : hm
}

/** `HH:MM:SS.mmm` for an ISO instant; a dash when the store sent none. */
export function formatClock(iso: string | null): string {
  const t = iso ? Date.parse(iso) : NaN
  return Number.isNaN(t) ? '-' : `${formatTime(t, true)}.${String(t % 1000).padStart(3, '0')}`
}

/** Up to five evenly spaced x-axis labels; seconds appear when the span is under ten minutes. */
export function timeLabels(times: number[]): string[] {
  if (!times.length) return []
  const seconds = times[times.length - 1] - times[0] < 600_000
  const n = Math.min(5, times.length)
  return Array.from({ length: n }, (_, k) =>
    formatTime(times[n === 1 ? 0 : Math.round((k * (times.length - 1)) / (n - 1))], seconds),
  )
}

export function summary({ title, sub, series }: { title: string; sub?: string; series: Series[] }, data: PlotData, format: (v: number) => string) {
  const latest = series.flatMap((s, i) => {
    const v = data.values[i].findLast((x) => x !== undefined)
    return v === undefined ? [] : [`${s.label} ${format(v)}`]
  })
  return `${[title, sub].filter(Boolean).join(' ')}: ${latest.join(', ')}`
}

export function tooltip(t: number, parts: string[]) {
  return `${formatTime(t, true)}\n${parts.join('\n')}`
}
