import { useSession } from '@/api/auth'
import { Button } from '@/components/ui/button'

/** Placeholder frame for the signed-in console; the sidebar and header replace it. */
export default function Shell({ onSignOut }: { onSignOut: () => void }) {
  const { email } = useSession()
  return (
    <header className="flex min-h-[60px] items-center gap-3 border-b border-bd bg-bg px-6">
      <span className="size-[18px] rounded bg-tx" aria-hidden />
      <span className="text-sm font-semibold">Warehouse console</span>
      <span className="ml-auto text-mu">{email}</span>
      <Button variant="outline" size="sm" onClick={onSignOut}>
        Sign out
      </Button>
    </header>
  )
}
