import { Outlet } from 'react-router'
import { LiveProvider } from '@/data/live'
import { StreamProvider } from '@/data/stream/StreamProvider'
import Sidebar from './Sidebar'

/** Signed-in frame: sidebar on the left, the routed page (with its own Header) on the right. */
export default function Shell({ onSignOut }: { onSignOut: () => void }) {
  return (
    <LiveProvider>
      <StreamProvider>
        <div className="flex h-screen">
          <Sidebar onSignOut={onSignOut} />
          <main className="flex min-w-0 flex-1 flex-col overflow-auto bg-bg2">
            <Outlet />
          </main>
        </div>
      </StreamProvider>
    </LiveProvider>
  )
}
