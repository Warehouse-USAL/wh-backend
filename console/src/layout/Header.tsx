import { RANGES, type Range, useLive } from '@/data/live'
import { cn } from '@/lib/utils'

const RANGE_LABEL: Record<Range, string> = {
  '5m': 'Past 5 minutes',
  '15m': 'Past 15 minutes',
  '1h': 'Past hour',
  '6h': 'Past 6 hours',
  '24h': 'Past 24 hours',
}

interface HeaderProps {
  crumb: string
  title: string
  /** Tab labels; the first is active unless `tab` says otherwise. */
  tabs?: string[]
  tab?: string
  onTab?: (tab: string) => void
  chips?: string[]
}

export default function Header({ crumb, title, tabs, tab, onTab, chips }: HeaderProps) {
  const { live, range, toggle, setRange } = useLive()
  const activeTab = tab ?? tabs?.[0]
  return (
    <header className="border-b border-bd bg-bg px-6">
      <div className="flex min-h-[60px] items-center gap-3">
        <span className="text-mu">{crumb} /</span>
        <h1 className="m-0 text-xl font-semibold tracking-[-0.02em]">{title}</h1>
        {chips?.map((c) => (
          <span key={c} className="rounded-full border border-bd px-2.5 py-0.5 text-xs text-mu">
            {c}
          </span>
        ))}
        <div className="flex-1" />
        <select
          name="range"
          aria-label="Time range"
          value={range}
          onChange={(e) => setRange(e.target.value as Range)}
          className="min-h-8 rounded-md border border-bd2 bg-bg px-3 text-[13px]"
        >
          {RANGES.map((r) => (
            <option key={r} value={r}>
              {RANGE_LABEL[r]}
            </option>
          ))}
        </select>
        <button
          type="button"
          aria-pressed={live}
          aria-label="Live updates"
          onClick={toggle}
          className={cn('flex items-center gap-1.5 text-[13px] font-medium', live ? 'text-ok' : 'text-mu')}
        >
          <span className="size-[7px] rounded-full bg-current" aria-hidden />
          Live
        </button>
      </div>
      {tabs && (
        <div role="tablist" className="flex gap-[22px]">
          {tabs.map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={t === activeTab}
              onClick={() => onTab?.(t)}
              className="border-b-2 border-transparent pt-2 pb-2.5 text-[13px] text-mu aria-selected:border-tx aria-selected:text-tx"
            >
              {t}
            </button>
          ))}
        </div>
      )}
    </header>
  )
}
