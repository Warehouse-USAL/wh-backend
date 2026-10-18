import { useSyncExternalStore } from 'react'
import { ApiError, api, getSession, setSession, subscribe } from './client'

interface Claims {
  email?: unknown
  role?: unknown
}

/** Reads the JWT payload without verifying it; the backend verifies every request. */
function claimsOf(token: string): Claims {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(payload), (c) => c.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes)) as Claims
  } catch {
    return {}
  }
}

/** Logs in through `POST /auth/login` and keeps the session only for a SUPERADMIN token. */
export async function login(email: string, password: string): Promise<void> {
  const body = (await api('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) })) as {
    token?: unknown
  } | null
  const token = typeof body?.token === 'string' ? body.token : ''
  const claims = claimsOf(token)
  if (claims.role !== 'SUPERADMIN') {
    throw new ApiError(403, 'NOT_SUPERADMIN', 'This console is only for SUPERADMIN accounts.')
  }
  setSession({ token, email: typeof claims.email === 'string' ? claims.email : email })
}

const logout = () => setSession(null)

export function useSession(): { token: string | null; email: string | null; logout: () => void } {
  const session = useSyncExternalStore(subscribe, getSession)
  return { token: session?.token ?? null, email: session?.email ?? null, logout }
}
