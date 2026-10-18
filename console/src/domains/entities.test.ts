import { describe, expect, it, vi } from 'vitest'
import { NAV } from '@/layout/nav'
import { jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'
import files from './__fixtures__/files.json'
import lines from './__fixtures__/lines.json'
import orders from './__fixtures__/orders.json'
import positions from './__fixtures__/positions.json'
import products from './__fixtures__/products.json'
import receptions from './__fixtures__/receptions.json'
import restockOrders from './__fixtures__/restock_orders.json'
import users from './__fixtures__/users.json'
import vehicles from './__fixtures__/vehicles.json'
import zones from './__fixtures__/zones.json'
import { DOMAIN_PAGES, ENTITIES, type EntityConfig, fieldOf, fillPath } from './entities'
import { type OpenApiDoc, pickRequest } from './template'

// Real responses recorded from the demo-seeded backend (GET with page=0&size=2, lists cut to two rows).
const RECORDED: Record<string, Record<string, unknown>> = {
  users, products, orders, vehicles, zones, lines, positions, restock_orders: restockOrders, receptions, files,
}

const entity = (key: string): EntityConfig => {
  const found = ENTITIES.find((e) => e.key === key)
  if (!found) throw new Error(`no entity ${key}`)
  return found
}
const paths = spec.paths as Record<string, Record<string, unknown>>
const exists = (method: string, path: string) => Boolean(paths[path]?.[method.toLowerCase()])

describe('ENTITIES', () => {
  it('entityActionsMatchTheEndpointsThatExist', () => {
    expect(ENTITIES.map((e) => e.key)).toEqual([
      'users', 'products', 'orders', 'vehicles', 'zones', 'lines', 'positions', 'restock_orders', 'receptions', 'files',
    ])
    for (const key of ['users', 'orders', 'vehicles', 'restock_orders', 'receptions']) expect(entity(key).remove).toBeUndefined()
    expect(entity('restock_orders')).toMatchObject({ create: { method: 'POST', path: '/restock/orders' }, extras: [] })
    expect(entity('restock_orders').edit).toBeUndefined()
    expect(entity('receptions').create).toBeDefined()
    expect(entity('receptions').edit).toEqual({ method: 'PATCH', path: '/restock/receptions/{id}' })
    expect(entity('users').extras.map((x) => x.label)).toEqual(['Reset password'])
    expect(entity('orders').edit).toEqual({ method: 'PATCH', path: '/internal/orders/{id}/status' })
    expect(entity('orders').extras.map((x) => x.label)).toEqual(['Cancel', 'Assign vehicle'])
    expect(entity('positions').extras).toMatchObject([{ label: 'Validate fit', inspect: true, endpoint: { method: 'POST', path: '/warehouse/positions/validate-fit' } }])
    expect(entity('products').search).toBe('search')
    expect(entity('files')).toMatchObject({
      create: { method: 'POST', path: '/api/v1/files/upload' },
      remove: { method: 'DELETE', path: '/api/v1/files/{path}/{filename}' },
      download: '/api/v1/files/{path}/{filename}',
    })
    expect(entity('files').edit).toBeUndefined()

    for (const e of ENTITIES) {
      const writes = [e.create, e.edit, e.remove, ...e.extras.map((x) => x.endpoint)].filter((w) => w !== undefined)
      for (const w of writes) expect(exists(w.method, w.path), `${e.key}: ${w.method} ${w.path}`).toBe(true)
      for (const read of [e.listPath, e.get?.path, e.download].filter((p) => p !== undefined)) {
        expect(exists('GET', read), `${e.key}: GET ${read}`).toBe(true)
      }
    }
  })

  it('listKeyAndFieldsMatchTheRecordedResponses', () => {
    for (const e of ENTITIES) {
      const rows = RECORDED[e.key][e.listKey]
      expect(Array.isArray(rows), `${e.key}.${e.listKey}`).toBe(true)
      const row = (rows as Record<string, unknown>[])[0]
      expect(row[e.idField], `${e.key} id`).toEqual(expect.any(String))
      for (const c of e.columns) expect(c.field.split('.')[0] in row, `${e.key}.${c.field}`).toBe(true)
    }
  })

  it('mapsEachDomainsNavPathToItsEntities', () => {
    const domainPaths = NAV.find((s) => s.section === 'Domains')?.items.map((i) => i.path)
    expect(Object.keys(DOMAIN_PAGES)).toEqual(domainPaths)
    expect(DOMAIN_PAGES['/domains/warehouse'].map((e) => e.title)).toEqual(['Zones', 'Lines', 'Positions'])
    expect(DOMAIN_PAGES['/domains/restock'].map((e) => e.title)).toEqual(['Restock orders', 'Receptions'])
  })
})

describe('edit prefill', () => {
  const editBody = (key: string) => {
    const e = entity(key)
    const doc = (RECORDED[key][e.listKey] as Record<string, unknown>[])[0]
    if (!e.edit) throw new Error(`${key} has no edit`)
    return pickRequest(spec as OpenApiDoc, e.edit, e.toRequest?.(doc) ?? doc)
  }

  it('mapsRenamedFieldsAndKeepsOnlyWhatTheEditEndpointAccepts', () => {
    expect(editBody('vehicles')).toEqual({
      status: 'idle', position_x: 12.5, position_y: 4, battery: 96, last_seen_at: vehicles.vehicles[0].last_seen_at,
    })
    expect(editBody('orders')).toEqual({ status: 'completed', completed_at: '2024-10-18T15:42:00Z' })
    expect(editBody('users')).toEqual({ name: 'System Admin', role: 'SUPERADMIN', active: true })
    const product = editBody('products')
    expect(product).toMatchObject({ is_active: true, minimum_stock: 50, max_quantity_per_order: 20, name: products.products[0].name })
    expect(Object.keys(product)).not.toEqual(expect.arrayContaining(['id', 'sku', 'stock', 'active']))
    expect(editBody('receptions')).toEqual({ assignments: receptions.receptions[0].assignments })
  })
})

describe('files', () => {
  it('formatsSizesAndOnlyActsOnSingleSegmentKeys', () => {
    const files = entity('files')
    const size = files.columns.find((c) => c.field === 'size')
    expect([69, 2048, 3 * 1024 * 1024].map((n) => size?.format?.(n))).toEqual(['69 B', '2.0 KB', '3.0 MB'])
    expect(files.actionable?.({ path: 'images', filename: 'a.png' })).toBe(true)
    expect(files.actionable?.({ path: '', filename: 'root.png' })).toBe(false)
    expect(files.actionable?.({ path: 'a/b', filename: 'c.png' })).toBe(false)
  })
})

describe('scopes', () => {
  it('listsZonesForLinesAndEveryZonesLinesForPositions', async () => {
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url === '/api/warehouse/zones') return jsonResponse(200, zones)
      return jsonResponse(200, url.includes('z-B') ? lines : { lines: [] })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(entity('lines').scope?.options()).resolves.toEqual([
      { value: '6ad521e021cc8a6b3f2b69ca', label: 'Zone A' },
      { value: 'z-B', label: 'Zone B' },
    ])
    await expect(entity('positions').scope?.options()).resolves.toEqual([
      { value: 'l-B-1', label: 'Zone B, line 1' },
      { value: 'l-B-2', label: 'Zone B, line 2' },
    ])
  })
})

describe('fillPath', () => {
  it('fillsEachPlaceholderEncoded', () => {
    expect(fillPath('/api/v1/files/{path}/{filename}', { path: 'images', filename: 'a b.png' })).toBe(
      '/api/v1/files/images/a%20b.png',
    )
    expect(fillPath('/users/{id}', { id: 'u/1' })).toBe('/users/u%2F1')
  })

  it('readsDottedFields', () => {
    expect(fieldOf({ stock: { available: 3 } }, 'stock.available')).toBe(3)
    expect(fieldOf({ stock: null }, 'stock.available')).toBeUndefined()
  })
})
