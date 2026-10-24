import type { ServiceState, ServiceStatus } from '@/data/streamHooks'

/** `GET /console/status` (and the stream's `status` payload) as the backend sends it. */
export interface WireStatus {
  dev: boolean
  services: { name: string; state: ServiceState; detail: string | null; uptime_ms: number | null }[]
}

export function toStatus({ dev, services }: WireStatus): { services: ServiceStatus[]; dev: boolean } {
  return { dev, services: services.map(({ uptime_ms, ...rest }) => ({ ...rest, uptimeMs: uptime_ms })) }
}
