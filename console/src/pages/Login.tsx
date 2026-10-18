import { useState, type FormEvent } from 'react'
import { login } from '@/api/auth'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import ThemeToggle from '@/layout/ThemeToggle'

// The proxy answers these when the backend is down; their bodies are nginx pages, not API errors.
const GATEWAY_ERRORS = new Set([502, 503, 504])

export default function Login({ notice }: { notice?: string }) {
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setPending(true)
    setError(null)
    try {
      await login(String(form.get('email')), String(form.get('password')))
    } catch (e) {
      setError(e instanceof ApiError && !GATEWAY_ERRORS.has(e.status) ? e.message : 'Cannot reach the server. Try again.')
      setPending(false)
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center p-6">
      <ThemeToggle className="absolute top-4 right-4" />
      <form onSubmit={submit} className="flex w-full max-w-sm flex-col gap-4 rounded-lg border border-bd bg-bg p-6">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <span className="size-[18px] rounded bg-tx" aria-hidden />
          Warehouse console
        </div>
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Sign in</h1>
          <p className="text-mu">SUPERADMIN accounts only.</p>
        </div>
        {notice && (
          <p role="status" className="rounded-md border border-bd bg-bg2 px-3 py-2 text-mu">
            {notice}
          </p>
        )}
        <label className="flex flex-col gap-1.5 font-medium" htmlFor="email">
          Email
          <Input id="email" name="email" type="email" autoComplete="username" required />
        </label>
        <label className="flex flex-col gap-1.5 font-medium" htmlFor="password">
          Password
          <Input id="password" name="password" type="password" autoComplete="current-password" required />
        </label>
        {error && (
          <p role="alert" className="text-er">
            {error}
          </p>
        )}
        <Button type="submit" loading={pending}>
          Sign in
        </Button>
      </form>
    </main>
  )
}
