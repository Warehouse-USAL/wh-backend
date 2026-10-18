import { useEffect, useState } from 'react'
import { Route, Routes } from 'react-router'
import { useSession } from '@/api/auth'
import { onUnauthorized } from '@/api/client'
import Shell from '@/layout/Shell'
import Login from '@/pages/Login'

// Login replaces the page without navigating, so the route survives an expired token.
export default function App() {
  const { token, logout } = useSession()
  const [expired, setExpired] = useState(false)
  useEffect(() => onUnauthorized(() => setExpired(true)), [])

  if (!token) return <Login notice={expired ? 'Your session expired. Sign in again.' : undefined} />

  const signOut = () => {
    setExpired(false)
    logout()
  }
  return (
    <Routes>
      <Route path="*" element={<Shell onSignOut={signOut} />} />
    </Routes>
  )
}
