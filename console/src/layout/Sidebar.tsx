import { NavLink } from 'react-router'
import { useSession } from '@/api/auth'
import { Button } from '@/components/ui/button'
import { NAV } from './nav'
import ThemeToggle from './ThemeToggle'

export default function Sidebar({ onSignOut }: { onSignOut: () => void }) {
  const { email } = useSession()
  return (
    <aside className="flex w-[232px] shrink-0 flex-col border-r border-bd bg-bg">
    <nav aria-label="Console" className="flex flex-1 flex-col gap-px overflow-y-auto px-2.5 pt-3.5 pb-2">
      <div className="flex items-center gap-2 px-2.5 pt-1 pb-4 text-sm font-semibold">
        <span className="size-[18px] rounded bg-tx" aria-hidden />
        wh-backend
        <span className="ml-auto rounded-full border border-bd px-2 text-[11px] font-medium text-mu">staging</span>
      </div>
      {NAV.map(({ section, hideLabel, items }) => (
        <div key={section} className="flex flex-col gap-px">
          {!hideLabel && (
            <div className="px-2.5 pt-3.5 pb-1 text-[13px] font-medium text-mu">{section}</div>
          )}
          {items.map(({ label, path, icon: Icon }) => (
            <NavLink
              key={path}
              to={path}
              className="flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-[13px] text-tx hover:bg-accent aria-[current=page]:bg-secondary aria-[current=page]:font-medium"
            >
              <Icon className="size-4 shrink-0 text-mu" strokeWidth={1.5} aria-hidden />
              {label}
            </NavLink>
          ))}
        </div>
      ))}
    </nav>
      <div className="flex flex-col gap-2 border-t border-bd px-5 py-3">
        <ThemeToggle />
        <span className="truncate text-xs text-mu">{email}</span>
        <Button variant="outline" size="sm" onClick={onSignOut}>
          Sign out
        </Button>
      </div>
    </aside>
  )
}
