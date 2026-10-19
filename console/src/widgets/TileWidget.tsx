import { cn } from '@/lib/utils'
import type { Tone } from './scale'

type Props = { value: string; label: string; tone: Tone; spark?: number[]; className?: string }

const TEXT: Record<Tone, string> = { ok: 'text-ok', warn: 'text-wn', error: 'text-er' }

/** Big number with a caption and an optional sparkline behind it. */
export default function TileWidget({ value, label, tone, spark, className }: Props) {
  const vs = (spark ?? []).filter(Number.isFinite)
  // Scaled from zero (or the lowest negative), not min to max: a 99.9 to 99.7 success rate stays a flat line.
  const lo = Math.min(0, ...vs)
  const span = Math.max(...vs) - lo
  const points = vs
    .map((v, i) => `${+((i / (vs.length - 1)) * 100).toFixed(2)},${+(span ? 34 - ((v - lo) / span) * 28 : 20).toFixed(2)}`)
    .join(' ')

  return (
    <div className={cn('relative flex min-h-[150px] min-w-0 flex-col items-center justify-center overflow-hidden rounded-lg border border-bd bg-bg text-center', className)}>
      {vs.length > 1 && (
        <svg viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden className={cn('absolute inset-x-0 bottom-0 h-[42%] w-full opacity-50', TEXT[tone])}>
          <polyline points={points} fill="none" stroke="currentColor" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
        </svg>
      )}
      <div className={cn('relative text-[32px] leading-tight font-semibold tracking-[-0.03em]', TEXT[tone])}>{value}</div>
      <div className="relative mt-0.5 px-2 text-xs text-mu">{label}</div>
    </div>
  )
}
