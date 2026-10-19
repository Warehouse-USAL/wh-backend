import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

type CardProps = {
  title: ReactNode
  sub?: ReactNode
  right?: ReactNode
  /** Skips the padded body so rows can run edge to edge. */
  flush?: boolean
  className?: string
  children: ReactNode
}

export function Empty() {
  return <p className="py-6 text-center text-xs text-mu">No data in this range</p>
}

/** The bordered widget frame: header line with a muted sub-title, then the body. */
export default function Card({ title, sub, right, flush, className, children }: CardProps) {
  return (
    <div className={cn('flex min-w-0 flex-col rounded-lg border border-bd bg-bg', className)}>
      <div className="flex items-center gap-2 px-3 pt-2.5 pb-1 text-[13px] font-medium">
        {title}
        {sub && <span className="text-xs font-normal text-mu">{sub}</span>}
        {right && <span className="ml-auto text-xs font-normal">{right}</span>}
      </div>
      {flush ? children : <div className="flex-1 px-3 pt-0.5 pb-3">{children}</div>}
    </div>
  )
}
