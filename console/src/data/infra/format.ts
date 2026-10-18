const trim = (v: number, digits: number) => String(+v.toFixed(digits))

const BYTES = [
  [1e12, 'TB'],
  [1e9, 'GB'],
  [1e6, 'MB'],
  [1e3, 'KB'],
] as const

/** Decimal units, as MinIO and VictoriaMetrics report sizes. */
export function formatBytes(v: number): string {
  const unit = BYTES.find(([size]) => v >= size)
  return unit ? `${trim(v / unit[0], 1)} ${unit[1]}` : `${trim(v, 0)} B`
}

/** The two largest units: `2d 4h`, `3h 12m`, `5m`, `40s`. */
export function formatUptime(ms: number): string {
  const s = Math.floor(ms / 1000)
  const [d, h, m] = [Math.floor(s / 86_400), Math.floor((s % 86_400) / 3600), Math.floor((s % 3600) / 60)]
  if (d) return `${d}d ${h}h`
  if (h) return `${h}h ${m}m`
  return m ? `${m}m` : `${s}s`
}

/** How long ago, coarse: `20 s`, `2 min`, `3 h`. */
export function formatAge(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s} s`
  if (s < 3600) return `${Math.floor(s / 60)} min`
  return `${Math.floor(s / 3600)} h`
}

export const percent = (v: number) => `${+v.toFixed(2)}%`
