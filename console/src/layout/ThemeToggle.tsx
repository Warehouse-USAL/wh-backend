import { Monitor, Moon, Sun, type LucideIcon } from 'lucide-react'
import { type ThemeChoice, useTheme } from '@/lib/theme'
import { cn } from '@/lib/utils'

const CHOICES: { value: ThemeChoice; label: string; icon: LucideIcon }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
]

export default function ThemeToggle({ className }: { className?: string }) {
  const { theme, setTheme } = useTheme()
  return (
    <div role="group" aria-label="Theme" className={cn('inline-flex rounded-md border border-bd bg-bg2 p-0.5', className)}>
      {CHOICES.map(({ value, label, icon: Icon }) => (
        <button
          key={value}
          type="button"
          aria-label={label}
          title={label}
          aria-pressed={theme === value}
          onClick={() => setTheme(value)}
          className={cn(
            'flex flex-1 items-center justify-center rounded-[5px] border border-transparent px-2 py-1.5 text-mu outline-none hover:text-tx focus-visible:ring-2 focus-visible:ring-blue',
            theme === value && 'border-tx/60 bg-bg text-tx shadow-xs',
          )}
        >
          <Icon className="size-3.5" strokeWidth={1.5} aria-hidden />
        </button>
      ))}
    </div>
  )
}
