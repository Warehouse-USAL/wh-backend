import { type OpenApiDoc, loadSpec } from '@/domains/template'

/** The Monitoring pages that show HTTP endpoints, named as in the sidebar. */
export const DOMAINS = [
  'Users and auth',
  'Products',
  'Orders',
  'Vehicles',
  'Warehouse',
  'Restock',
  'Files',
  'Metrics and query',
  'Infra callbacks',
] as const
export type Domain = (typeof DOMAINS)[number]

/**
 * Controller `@Tag` -> [page, section on that page]. Its order is the order of the routes on a page.
 * A new tag fails `everyRouteInTheSpecLandsOnExactlyOneDomainPage` until it is listed here or in
 * `EXCLUDED_TAGS`; at runtime it shows under "Other" on the nearest page instead of vanishing.
 */
export const TAGS: Record<string, [Domain, string]> = {
  Authentication: ['Users and auth', 'Auth'],
  Users: ['Users and auth', 'Users'],
  Products: ['Products', 'Products'],
  Orders: ['Orders', 'Orders'],
  Internal: ['Orders', 'Orders'],
  Vehicles: ['Vehicles', 'Vehicles'],
  'Internal - Vehicles': ['Vehicles', 'Vehicles'],
  'Warehouse - Zones': ['Warehouse', 'Zones'],
  'Warehouse - Lines': ['Warehouse', 'Lines'],
  'Warehouse - Positions': ['Warehouse', 'Positions'],
  'Restock Orders': ['Restock', 'Restock orders'],
  Receptions: ['Restock', 'Receptions'],
  Files: ['Files', 'Files'],
  Metrics: ['Metrics and query', 'Metrics'],
  Query: ['Metrics and query', 'Generic query (dashboard only)'],
  Infra: ['Infra callbacks', 'Service to service, X-Api-Key'],
}

/** The console's own endpoints are the console's plumbing, not part of the monitored API. */
export const EXCLUDED_TAGS = ['Console']

export interface RouteInfo {
  method: string
  /** The path template, equal to the `route` tag the backend records (Spring's matched pattern). */
  path: string
  summary?: string
  domain: Domain
  section: string
  /** `public` when the operation needs no credentials, else its security schemes. */
  auth: string
}

const OTHER = 'Other'
const SCHEMES: Record<string, string> = { 'bearer-jwt': 'JWT', 'infra-api-key': 'X-Api-Key' }
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const TAG_ORDER = Object.keys(TAGS)

const commonPrefix = (a: string, b: string) => {
  let i = 0
  while (i < a.length && a[i] === b[i]) i++
  return i
}

/** One route per operation of the spec, ordered by page, tag (TAGS order, "Other" last), path and method. */
export function routesOf(spec: OpenApiDoc): RouteInfo[] {
  const unmapped: Omit<RouteInfo, 'domain' | 'section'>[] = []
  const ranked: [number, RouteInfo][] = []
  for (const [path, ops] of Object.entries(spec.paths)) {
    for (const [verb, op] of Object.entries(ops ?? {})) {
      const tags = op?.tags ?? []
      if (tags.some((t) => EXCLUDED_TAGS.includes(t))) continue
      const schemes = (op?.security ?? []).flatMap(Object.keys).map((s) => SCHEMES[s] ?? s)
      const route = { method: verb.toUpperCase(), path, summary: op?.summary, auth: schemes.join(', ') || 'public' }
      const tag = tags.find((t) => t in TAGS)
      if (tag) ranked.push([TAG_ORDER.indexOf(tag), { ...route, domain: TAGS[tag][0], section: TAGS[tag][1] }])
      else unmapped.push(route)
    }
  }
  for (const route of unmapped) {
    const nearest = ranked.reduce<RouteInfo | undefined>(
      (a, [, b]) => (!a || commonPrefix(b.path, route.path) > commonPrefix(a.path, route.path) ? b : a),
      undefined,
    )
    ranked.push([TAG_ORDER.length, { ...route, domain: nearest?.domain ?? DOMAINS[0], section: OTHER }])
  }
  const byPath = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)
  return ranked
    .sort(([t, a], [u, b]) =>
      DOMAINS.indexOf(a.domain) - DOMAINS.indexOf(b.domain) || t - u || byPath(a.path, b.path) || METHODS.indexOf(a.method) - METHODS.indexOf(b.method),
    )
    .map(([, route]) => route)
}

/** The API's routes from springdoc's document; rejects when the document cannot be loaded. */
export async function loadRoutes(): Promise<RouteInfo[]> {
  const spec = await loadSpec()
  if (!spec) throw new Error('API description unavailable')
  return routesOf(spec)
}
