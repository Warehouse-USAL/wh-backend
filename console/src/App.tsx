import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { useSession } from '@/api/auth'
import { onUnauthorized } from '@/api/client'
import { DOMAIN_PAGES } from '@/domains/entities'
import EntityPage from '@/domains/EntityPage'
import Header from '@/layout/Header'
import { NAV } from '@/layout/nav'
import Shell from '@/layout/Shell'
import MonitoringPage from '@/monitoring/MonitoringPage'
import RequestTrail from '@/monitoring/RequestTrail'
import { DOMAINS, type Domain } from '@/monitoring/routes'
import DevTool from '@/pages/DevTool'
import Login from '@/pages/Login'
import Logs from '@/pages/Logs'
import Overview from '@/pages/Overview'
import WebSockets from '@/pages/WebSockets'

// A Monitoring nav item whose label names an API domain gets that domain's page.
const isDomain = (label: string): label is Domain => (DOMAINS as readonly string[]).includes(label)

// Dev tools embed through the console nginx's frame-safe proxies (Task 17). The dev profile is
// http only: the CSP allows `frame-src http://*:8091 http://*:8092`, so an https console would need
// that CSP changed (see Task 29).
const DEV_PORTS: Record<string, number> = { '/dev/mongo-express': 8091, '/dev/redpanda-console': 8092 }

// Stands in for a page until its task builds it; tasks 19-25 swap it out route by route.
function Placeholder({ crumb, title }: { crumb: string; title: string }) {
  return <Header crumb={crumb} title={title} />
}

function NotFound() {
  return (
    <>
      <Header crumb="Console" title="Page not found" />
      <p className="px-6 py-4 text-mu">There is nothing at this address.</p>
    </>
  )
}

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
      <Route element={<Shell onSignOut={signOut} />}>
        <Route path="/" element={<Navigate to="/overview" replace />} />
        {NAV.flatMap(({ section, items }) =>
          items.map(({ label, path }) => (
            <Route
              key={path}
              path={path}
              element={
                path in DOMAIN_PAGES ? (
                  <EntityPage title={label} entities={DOMAIN_PAGES[path]} />
                ) : section === 'Monitoring' && isDomain(label) ? (
                  <MonitoringPage key={path} domain={label} extra={label === 'Orders' ? <RequestTrail /> : undefined} />
                ) : path === '/infra/logs' ? (
                  <Logs />
                ) : path === '/overview' ? (
                  <Overview />
                ) : path === '/monitoring/websockets' ? (
                  <WebSockets />
                ) : path in DEV_PORTS ? (
                  <DevTool key={path} name={label} port={DEV_PORTS[path]} />
                ) : (
                  <Placeholder crumb={section} title={label} />
                )
              }
            />
          )),
        )}
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  )
}
