import { api } from '@/api/client'

/** A write endpoint as springdoc lists it; `{name}` placeholders are filled from the row or the scope. */
export type Endpoint = { method: 'POST' | 'PATCH' | 'DELETE'; path: string }

export interface Column {
  header: string
  /** Dotted path into the row, e.g. `stock.available`. */
  field: string
  badge?: boolean
  format?(value: unknown): string
}

type Row = Record<string, unknown>

export interface Extra {
  label: string
  endpoint: Endpoint
  /** A read-only check: the modal shows the response instead of closing. */
  inspect?: boolean
  /** Request fields taken from the row, laid over the schema template. */
  prefill?(row: Row): Row
}

export interface ScopeOption {
  value: string
  label: string
}

/** A parent the list and create endpoints need in their path (lines live in a zone, positions in a line). */
export interface Scope {
  param: string
  label: string
  options(): Promise<ScopeOption[]>
}

export interface EntityConfig {
  key: string
  title: string
  /** Singular, for "New product" and "Delete product ...". */
  noun: string
  source: string
  listPath: string
  listKey: string
  idField: string
  columns: Column[]
  /** Where Edit reads the current document (`key` unwraps `{product: {...}}`); without it Edit starts from the row. */
  get?: { path: string; key?: string }
  scope?: Scope
  create?: Endpoint
  edit?: Endpoint
  remove?: Endpoint
  extras: Extra[]
  /** The list endpoint's free-text query parameter, when it has one. */
  search?: string
  /** Query parameter that narrows the list to inactive rows (the backend defaults to active only). */
  inactive?: string
  /** Renames document fields to the request's names before Edit keeps only the fields the request accepts. */
  toRequest?(doc: Row): Row
  /** False when the row's endpoints cannot address it (Files keys outside one safe folder). */
  actionable?(row: Row): boolean
  /** Create is a multipart upload instead of a JSON body. */
  upload?: boolean
  download?: string
}

const col = (header: string, field: string, badge?: boolean): Column => ({ header, field, badge })
const post = (path: string): Endpoint => ({ method: 'POST', path })
const patch = (path: string): Endpoint => ({ method: 'PATCH', path })
const del = (path: string): Endpoint => ({ method: 'DELETE', path })

interface Zone {
  id_zone: string
  zone_code: string
}
interface Line {
  id_line: string
  number_line: number
}

async function zones(): Promise<Zone[]> {
  return ((await api('/warehouse/zones')) as { zones: Zone[] }).zones
}

async function zoneOptions(): Promise<ScopeOption[]> {
  return (await zones()).map((z) => ({ value: z.id_zone, label: `Zone ${z.zone_code}` }))
}

// There is no flat lines endpoint, so the line picker walks every zone.
async function lineOptions(): Promise<ScopeOption[]> {
  const perZone = await Promise.all(
    (await zones()).map(async (z) => {
      const { lines } = (await api(fillPath('/warehouse/zones/{zoneId}/lines', { zoneId: z.id_zone }))) as { lines: Line[] }
      return lines.map((l) => ({ value: l.id_line, label: `Zone ${z.zone_code}, line ${l.number_line}` }))
    }),
  )
  return perZone.flat()
}

// FileController is mapped under /api/v1 itself, so through the console's /api prefix it is /api/api/v1/files.
const FILE_PATH = '/api/v1/files/{path}/{filename}'
const SEGMENT = /^(?!\.+$)[A-Za-z0-9._-]+$/ // dots-only segments (`.`, `..`) would climb out of the folder

function bytes(value: unknown): string {
  const n = Number(value)
  if (n < 1024) return `${n} B`
  return n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`
}

export const ENTITIES: EntityConfig[] = [
  {
    key: 'users', title: 'Users', noun: 'user', source: 'collection users',
    listPath: '/users', listKey: 'users', idField: 'id',
    columns: [col('Id', 'id'), col('Email', 'email'), col('Name', 'name'), col('Role', 'role', true), col('Active', 'active'), col('Created', 'created_at')],
    get: { path: '/users/{id}' },
    create: post('/users'),
    edit: patch('/users/{id}'),
    extras: [{ label: 'Reset password', endpoint: post('/users/{id}/reset-password') }],
  },
  {
    key: 'products', title: 'Products', noun: 'product', source: 'collection products',
    listPath: '/products', listKey: 'products', idField: 'id',
    columns: [col('Id', 'id'), col('Name', 'name'), col('SKU', 'sku'), col('Category', 'category'), col('Stock', 'stock.available'), col('Active', 'active')],
    get: { path: '/products/{id}', key: 'product' },
    search: 'search',
    inactive: 'isActive',
    toRequest: (doc) => ({
      ...doc,
      is_active: doc.active,
      minimum_stock: fieldOf(doc, 'stock.min'),
      max_quantity_per_order: fieldOf(doc, 'order_constraints.max_quantity_per_order'),
    }),
    create: post('/products'),
    edit: patch('/products/{id}'),
    remove: del('/products/{id}'),
    extras: [],
  },
  {
    key: 'orders', title: 'Orders', noun: 'order', source: 'collection orders',
    listPath: '/orders', listKey: 'orders', idField: 'id',
    columns: [col('Id', 'id'), col('Status', 'status', true), col('Destination', 'destination_area'), col('Vehicle', 'assigned_vehicle_id'), col('Priority', 'priority'), col('Created', 'timestamps.created_at')],
    get: { path: '/orders/{id}', key: 'order' },
    toRequest: (doc) => ({ ...doc, completed_at: fieldOf(doc, 'timestamps.completed_at') }),
    create: post('/orders'),
    edit: patch('/internal/orders/{id}/status'),
    extras: [
      { label: 'Cancel', endpoint: post('/orders/{id}/cancel') },
      { label: 'Assign vehicle', endpoint: patch('/internal/orders/{id}/assign-vehicle') },
    ],
  },
  {
    key: 'vehicles', title: 'Vehicles', noun: 'vehicle', source: 'collection vehicles',
    listPath: '/vehicles', listKey: 'vehicles', idField: 'id',
    columns: [col('Id', 'id'), col('Name', 'name'), col('Status', 'status', true), col('Battery', 'battery'), col('Order', 'current_order_id'), col('Last seen', 'last_seen_at')],
    get: { path: '/vehicles/{id}' },
    toRequest: (doc) => ({ ...doc, position_x: fieldOf(doc, 'position.x'), position_y: fieldOf(doc, 'position.y') }),
    create: post('/vehicles'),
    edit: patch('/internal/vehicles/{id}'),
    extras: [],
  },
  {
    key: 'zones', title: 'Zones', noun: 'zone', source: 'collection zones',
    listPath: '/warehouse/zones', listKey: 'zones', idField: 'id_zone',
    columns: [col('Id', 'id_zone'), col('Code', 'zone_code'), col('Max lines', 'max_allowed_lines'), col('Active', 'is_active'), col('Created', 'created_at')],
    create: post('/warehouse/zones'),
    edit: patch('/warehouse/zones/{id}'),
    remove: del('/warehouse/zones/{id}'),
    extras: [],
  },
  {
    key: 'lines', title: 'Lines', noun: 'line', source: 'collection lines',
    listPath: '/warehouse/zones/{zoneId}/lines', listKey: 'lines', idField: 'id_line',
    columns: [col('Id', 'id_line'), col('Number', 'number_line'), col('Zone', 'id_zone'), col('Max positions', 'max_allowed_positions'), col('Active', 'is_active')],
    scope: { param: 'zoneId', label: 'Zone', options: zoneOptions },
    create: post('/warehouse/zones/{zoneId}/lines'),
    edit: patch('/warehouse/lines/{id}'),
    remove: del('/warehouse/lines/{id}'),
    extras: [],
  },
  {
    key: 'positions', title: 'Positions', noun: 'position', source: 'collection positions',
    listPath: '/warehouse/lines/{lineId}/positions', listKey: 'positions', idField: 'id_position',
    columns: [col('Id', 'id_position'), col('Name', 'position_name'), col('Size', 'size_stock_to_save', true), col('Capacity', 'maximum_capacity'), col('Stock', 'current_stock'), col('Product', 'product_id')],
    get: { path: '/warehouse/positions/{id}' },
    scope: { param: 'lineId', label: 'Line', options: lineOptions },
    create: post('/warehouse/lines/{lineId}/positions'),
    edit: patch('/warehouse/positions/{id}'),
    remove: del('/warehouse/positions/{id}'),
    extras: [
      {
        label: 'Validate fit',
        endpoint: post('/warehouse/positions/validate-fit'),
        inspect: true,
        prefill: (row) => ({ product_id: row.product_id, size: row.size_stock_to_save }),
      },
    ],
  },
  {
    key: 'restock_orders', title: 'Restock orders', noun: 'restock order', source: 'collection restock_orders',
    listPath: '/restock/orders', listKey: 'restock_orders', idField: 'id',
    columns: [col('Id', 'id'), col('Product', 'product_id'), col('Supplier', 'supplier'), col('Requested', 'quantity_requested'), col('Created', 'created_at')],
    create: post('/restock/orders'),
    extras: [],
  },
  {
    key: 'receptions', title: 'Receptions', noun: 'reception', source: 'collection receptions',
    listPath: '/restock/receptions', listKey: 'receptions', idField: 'id',
    columns: [col('Id', 'id'), col('Restock order', 'restock_order_id'), col('Product', 'product_id'), col('Status', 'status', true), col('Received', 'quantity_received'), col('Unit', 'delivery_unit')],
    get: { path: '/restock/receptions/{id}', key: 'reception' },
    create: post('/restock/receptions'),
    edit: patch('/restock/receptions/{id}'),
    extras: [],
  },
  {
    key: 'files', title: 'Files', noun: 'file', source: 'MinIO bucket',
    listPath: '/api/v1/files', listKey: 'files', idField: 'filename',
    columns: [col('Path', 'path'), col('Filename', 'filename'), { header: 'Size', field: 'size', format: bytes }],
    create: post('/api/v1/files/upload'),
    remove: del(FILE_PATH),
    extras: [],
    actionable: (row) => SEGMENT.test(String(row.path)) && SEGMENT.test(String(row.filename)),
    upload: true,
    download: FILE_PATH,
  },
]

const pick = (...keys: string[]) => ENTITIES.filter((e) => keys.includes(e.key))

/** Each Domains nav path and the entities its page switches between. */
export const DOMAIN_PAGES: Record<string, EntityConfig[]> = {
  '/domains/users': pick('users'),
  '/domains/products': pick('products'),
  '/domains/orders': pick('orders'),
  '/domains/vehicles': pick('vehicles'),
  '/domains/warehouse': pick('zones', 'lines', 'positions'),
  '/domains/restock': pick('restock_orders', 'receptions'),
  '/domains/files': pick('files'),
}

/** Replaces each `{name}` with the URL-encoded value of `values[name]`. */
export function fillPath(path: string, values: Record<string, unknown>): string {
  return path.replace(/\{(\w+)\}/g, (_, name: string) => encodeURIComponent(String(values[name])))
}

export function fieldOf(row: Row, field: string): unknown {
  return field.split('.').reduce<unknown>((value, key) => (value as Record<string, unknown> | null)?.[key], row)
}
