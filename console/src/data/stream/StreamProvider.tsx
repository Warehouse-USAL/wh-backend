import { type ReactNode, useEffect, useState } from 'react'
import { useLive } from '../live'
import { StreamRegistry } from './registry'
import { RegistryContext } from './useStream'

/** The app's one push stream; inside `LiveProvider`, whose Live toggle pauses and resumes it. */
export function StreamProvider({ children }: { children: ReactNode }) {
  const { live } = useLive()
  const [registry] = useState(() => new StreamRegistry())
  useEffect(() => {
    registry.setLive(live)
    return () => registry.suspend()
  }, [registry, live])
  return <RegistryContext.Provider value={registry}>{children}</RegistryContext.Provider>
}
