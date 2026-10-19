import { createContext, useContext, useMemo, useSyncExternalStore } from 'react'
import { INITIAL, type StreamRegistry, type StreamStatus, type Spec, type SubState, canonical } from './registry'

export const RegistryContext = createContext<StreamRegistry | null>(null)

function useRegistry(): StreamRegistry {
  const registry = useContext(RegistryContext)
  if (!registry) throw new Error('Stream hooks must be used inside a StreamProvider')
  return registry
}

/** A `useSyncExternalStore` source over several subscriptions; its array keeps identity until one changes. */
function storeOf(registry: StreamRegistry, keys: string[]) {
  let cache: SubState[] = []
  return {
    subscribe: (listener: () => void) => {
      const offs = keys.map((k) => registry.subscribe(k, listener))
      return () => offs.forEach((off) => off())
    },
    get: () => {
      const next = keys.map((k) => registry.get(k))
      if (next.length !== cache.length || next.some((s, i) => s !== cache[i])) cache = next
      return cache
    },
  }
}

/** Holds one subscription per spec while mounted (ref-counted by canonical params); their states in order. */
export function useSubscriptions(specs: Spec[]): SubState[] {
  const registry = useRegistry()
  const keys = JSON.stringify(specs.map(canonical))
  const store = useMemo(() => storeOf(registry, JSON.parse(keys) as string[]), [registry, keys])
  return useSyncExternalStore(store.subscribe, store.get, store.get)
}

/** One subscription, or none while `spec` is null (its state then stays loading). */
export function useSubscription(spec: Spec | null): SubState {
  return useSubscriptions(spec ? [spec] : [])[0] ?? INITIAL
}

const IDLE: StreamStatus = { state: 'idle' }
const noSubscribe = () => () => undefined
const idle = () => IDLE

/** Connection state and the time of the last frame; idle outside a `StreamProvider`. */
export function useStreamStatus(): StreamStatus {
  const registry = useContext(RegistryContext)
  return useSyncExternalStore(registry?.onStatus ?? noSubscribe, registry?.getStatus ?? idle, registry?.getStatus ?? idle)
}
