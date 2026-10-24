import { ChevronDown } from 'lucide-react'
import { type ReactNode, useId } from 'react'

type Props = { title: ReactNode; tag?: ReactNode; right?: ReactNode; children: ReactNode }

/** Titled section whose children (widgets) sit on a 12-column grid; pass `col-span-N` as className. */
export default function Group({ title, tag, right, children }: Props) {
  const id = useId()
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2 py-0.5 text-[13px]">
        <ChevronDown aria-hidden className="size-3 text-mu" />
        <h3 id={id} className="font-semibold">{title}</h3>
        {tag && <span className="text-xs text-mu">{tag}</span>}
        {right && <div className="ml-auto text-xs">{right}</div>}
      </div>
      <div className="grid grid-cols-12 gap-3 max-[1100px]:[&>*]:col-span-12">{children}</div>
    </section>
  )
}
