import { beforeEach, describe, expect, it, vi } from 'vitest'
import { type OpenApiDoc, loadSpec } from '@/domains/template'
import spec from './__fixtures__/api-docs.json'
import recorded from './__fixtures__/http-routes.json'
import { DOMAINS, EXCLUDED_TAGS, TAGS, loadRoutes, routesOf } from './routes'

vi.mock('@/domains/template', () => ({ loadSpec: vi.fn() }))

const doc = spec as unknown as OpenApiDoc
const operations = Object.entries(doc.paths).flatMap(([path, ops]) =>
  Object.entries(ops ?? {}).map(([method, op]) => ({ key: `${method.toUpperCase()} ${path}`, tags: op?.tags ?? [] })),
)

describe('routes', () => {
  beforeEach(() => vi.mocked(loadSpec).mockResolvedValue(doc))

  it('everyRouteInTheSpecLandsOnExactlyOneDomainPage', () => {
    // A new controller tag fails here until it is mapped to a page or excluded on purpose.
    for (const { key, tags } of operations) {
      expect(tags.some((t) => Object.hasOwn(TAGS, t) || EXCLUDED_TAGS.includes(t)), key).toBe(true)
    }
    const keys = routesOf(doc).map((r) => `${r.method} ${r.path}`)
    const expected = operations.filter((o) => !o.tags.some((t) => EXCLUDED_TAGS.includes(t))).map((o) => o.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect([...keys].sort()).toEqual([...expected].sort())
    expect(operations.length - expected.length).toBe(3) // the /console/* endpoints, nothing else
    for (const domain of DOMAINS) expect(routesOf(doc).some((r) => r.domain === domain), domain).toBe(true)
  })

  it('aTagNamedLikeAnObjectBuiltInIsUnmappedNotAPage', () => {
    const odd = { paths: { '/orders/x': { get: { tags: ['constructor', 'toString'] } } } } as unknown as OpenApiDoc
    expect(routesOf({ paths: { ...doc.paths, ...odd.paths } } as OpenApiDoc).find((r) => r.path === '/orders/x')).toMatchObject({
      domain: 'Orders',
      section: 'Other',
    })
  })

  it('specPathsAreTheRouteTagsTheBackendRecords', () => {
    // Spring's matched pattern equals springdoc's path (same {var} names, no prefix), so no normalising.
    const specKeys = new Set(operations.map((o) => o.key))
    const extra = recorded.series.map((s) => `${s.labels.method} ${s.labels.route}`).filter((k) => !specKeys.has(k))
    expect(extra.sort()).toEqual(['GET /v3/api-docs', 'GET UNMATCHED'])
  })

  it('readsMethodSummarySectionAndAuthFromTheSpec', () => {
    const routes = routesOf(doc)
    const find = (method: string, path: string) => routes.find((r) => r.method === method && r.path === path)
    expect(find('POST', '/auth/login')).toEqual({
      method: 'POST', path: '/auth/login', summary: 'Login', domain: 'Users and auth', section: 'Auth', auth: 'public',
    })
    expect(find('PATCH', '/internal/orders/{id}/status')).toMatchObject({ domain: 'Orders', section: 'Orders', auth: 'JWT' })
    expect(find('POST', '/infra/orders/{orderId}/status')).toMatchObject({ domain: 'Infra callbacks', auth: 'X-Api-Key' })
    expect(find('GET', '/warehouse/zones')).toMatchObject({ section: 'Zones', summary: undefined })
  })

  it('ordersRoutesByTagThenPathThenMethod', () => {
    const keys = (domain: string) => routesOf(doc).filter((r) => r.domain === domain).map((r) => `${r.method} ${r.path}`)
    expect(keys('Users and auth').slice(0, 4)).toEqual(['POST /auth/login', 'GET /users', 'POST /users', 'PATCH /users/me'])
    // The Internal tag's order routes come after the Orders tag's, in the same section.
    expect(keys('Orders')).toEqual([
      'GET /orders',
      'POST /orders',
      'GET /orders/{id}',
      'POST /orders/{id}/cancel',
      'PATCH /internal/orders/{id}/assign-vehicle',
      'PATCH /internal/orders/{id}/status',
    ])
  })

  it('putsAnUnmappedTagUnderOtherOnTheNearestPage', () => {
    const extra: OpenApiDoc = {
      ...doc,
      paths: { ...doc.paths, '/products/{id}/images': { get: { tags: ['Brand new'], security: [{ custom: [] }] } } },
    }
    expect(routesOf(extra).find((r) => r.path === '/products/{id}/images')).toMatchObject({ domain: 'Products', section: 'Other', auth: 'custom' })
  })

  it('fallsBackToTheFirstPageWhenNothingIsMapped', () => {
    const lone: OpenApiDoc = { ...doc, paths: { '/new': { put: {} } } }
    expect(routesOf(lone)).toEqual([{ method: 'PUT', path: '/new', domain: 'Users and auth', section: 'Other', auth: 'public' }])
  })

  it('loadRoutesReadsTheSpecAndFailsWhenItIsUnavailable', async () => {
    await expect(loadRoutes()).resolves.toEqual(routesOf(doc))
    vi.mocked(loadSpec).mockResolvedValue(null)
    await expect(loadRoutes()).rejects.toThrow('API description unavailable')
  })
})
