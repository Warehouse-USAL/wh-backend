import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'
import files from './__fixtures__/files.json'
import lines from './__fixtures__/lines.json'
import products from './__fixtures__/products.json'
import receptions from './__fixtures__/receptions.json'
import restockOrders from './__fixtures__/restock_orders.json'
import users from './__fixtures__/users.json'
import zones from './__fixtures__/zones.json'
import { DOMAIN_PAGES } from './entities'
import EntityPage from './EntityPage'
import { type OpenApiDoc, requestTemplate } from './template'

type Handler = (init: RequestInit, url: string) => Response | Promise<Response>

/** Answers fetch by `METHOD /path` (query ignored) and records every call. */
function routes(table: Record<string, Handler>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init = {}) => {
    const url = String(input).split('?')[0]
    const handler = table[`${init.method ?? 'GET'} ${url}`]
    return handler ? handler(init, String(input)) : jsonResponse(404, { error: { code: 'NOT_FOUND', message: `no route ${url}` } })
  })
  vi.stubGlobal('fetch', fetchMock)
  const calls = (method: string, url: string) =>
    fetchMock.mock.calls.filter(([input, init = {}]) => (init.method ?? 'GET') === method && String(input).split('?')[0] === url)
  return { fetchMock, calls }
}

const SPEC: Handler = () => jsonResponse(200, spec)
const fileRow = files.files[0]
const fileUrl = `/api/api/v1/files/images/${fileRow.filename}`

const renderPage = (path: string, title: string) => render(<EntityPage title={title} entities={DOMAIN_PAGES[path]} />)
const editor = () => screen.getByRole('textbox', { name: 'JSON body' })

describe('EntityPage', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))

  it('listsTheRowsWithIdsInMonoAndTheTotal', async () => {
    const { calls } = routes({ 'GET /api/products': () => jsonResponse(200, products) })
    renderPage('/domains/products', 'Products')

    const id = await screen.findByRole('cell', { name: 'p-TEC-001' })
    expect(id).toHaveClass('font-mono')
    expect(screen.getByText('24 products')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Products' })).toBeInTheDocument()
    expect(screen.getByText('collection products')).toBeInTheDocument()
    expect(String(calls('GET', '/api/products')[0][0])).toBe('/api/products?page=0&size=25')
  })

  it('pagesWithThePaginationTheApiReturns', async () => {
    const { calls } = routes({
      'GET /api/products': (_init, url) => {
        const page = Number(new URL(url, 'http://console').searchParams.get('page'))
        return jsonResponse(200, { ...products, pagination: { page, size: 25, total_elements: 30, total_pages: 2 } })
      },
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(calls('GET', '/api/products').map(([input]) => String(input))).toEqual([
      '/api/products?page=0&size=25', '/api/products?page=1&size=25', '/api/products?page=0&size=25',
    ])
  })

  it('showsLoadingEmptyAndErrorStates', async () => {
    let answer: (r: Response) => void = () => undefined
    routes({ 'GET /api/products': () => new Promise<Response>((resolve) => (answer = resolve)) })
    renderPage('/domains/products', 'Products')

    expect(await screen.findByRole('status')).toHaveTextContent('Loading')
    answer(jsonResponse(200, { products: [], pagination: { page: 0, size: 25, total_elements: 0, total_pages: 0 } }))
    expect(await screen.findByText('No products yet.')).toBeInTheDocument()

    routes({ 'GET /api/products': () => jsonResponse(500, { error: { code: 'INTERNAL', message: 'Mongo is down' } }) })
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('INTERNAL: Mongo is down')
    expect(screen.queryByText(/^0 products/)).toBeNull()
  })

  it('newOpensWithTheTemplate', async () => {
    routes({ 'GET /api/products': () => jsonResponse(200, products), 'GET /api/v3/api-docs': SPEC })
    renderPage('/domains/products', 'Products')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'New product' }))

    const dialog = await screen.findByRole('dialog', { name: 'New product' })
    const template = requestTemplate(spec as OpenApiDoc, { method: 'POST', path: '/products' })
    expect(editor()).toHaveValue(JSON.stringify(template, null, 2))
    expect(within(dialog).getByText('POST /products')).toBeInTheDocument()
  })

  it('createsThroughTheCreateEndpoint', async () => {
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/v3/api-docs': SPEC,
      'POST /api/products': () => jsonResponse(201, { product: { id: 'p-NEW' } }),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'New product' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'JSON body' }), { target: { value: '{"sku": "X-1"}' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls('POST', '/api/products')[0][1]?.body).toBe('{"sku":"X-1"}')
  })

  it('editOpensWithTheCurrentDocument', async () => {
    const current = { id: 'p-TEC-001', name: 'Fetched just now', stock: { available: 7 } }
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/products/p-TEC-001': () => jsonResponse(200, { product: current }),
    })
    renderPage('/domains/products', 'Products')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))

    const dialog = await screen.findByRole('dialog', { name: 'products / p-TEC-001' })
    expect(editor()).toHaveValue(JSON.stringify(current, null, 2))
    expect(within(dialog).getByText('PATCH /products/{id}')).toBeInTheDocument()
    expect(calls('GET', '/api/products/p-TEC-001')).toHaveLength(1)
  })

  it('editsFromTheRowWhenThereIsNoGetEndpoint', async () => {
    routes({ 'GET /api/warehouse/zones': () => jsonResponse(200, zones) })
    renderPage('/domains/warehouse', 'Warehouse')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit z-B' }))

    expect(editor()).toHaveValue(JSON.stringify(zones.zones[1], null, 2))
  })

  it('reportsAFailedDocumentFetch', async () => {
    routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/products/p-TEC-001': () => jsonResponse(404, { error: { code: 'PRODUCT_NOT_FOUND', message: 'gone' } }),
    })
    renderPage('/domains/products', 'Products')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('PRODUCT_NOT_FOUND: gone')
  })

  it('tableRefetchesAfterASuccessfulWrite', async () => {
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/products/p-TEC-001': () => jsonResponse(200, { product: { id: 'p-TEC-001', name: 'Old' } }),
      'PATCH /api/products/p-TEC-001': () => jsonResponse(200, { product: { id: 'p-TEC-001', name: 'New' } }),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'JSON body' }), { target: { value: '{"name": "New"}' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls('GET', '/api/products')).toHaveLength(2))
    expect(calls('PATCH', '/api/products/p-TEC-001')[0][1]?.body).toBe('{"name":"New"}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('runsAnExtraActionWithItsTemplate', async () => {
    const { calls } = routes({
      'GET /api/users': () => jsonResponse(200, users),
      'GET /api/v3/api-docs': SPEC,
      'POST /api/users/u-system/reset-password': () => new Response(null, { status: 204 }),
    })
    renderPage('/domains/users', 'Users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Reset password u-system' }))
    expect(await screen.findByRole('dialog', { name: 'Reset password: u-system' })).toBeInTheDocument()
    expect(editor()).toHaveValue('{\n  "new_password": ""\n}')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls('POST', '/api/users/u-system/reset-password')).toHaveLength(1))
    await waitFor(() => expect(calls('GET', '/api/users')).toHaveLength(2))
  })

  it('deletesAfterConfirmingAndKeepsErrorsInTheDialog', async () => {
    let fail = true
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, products),
      'DELETE /api/products/p-TEC-001': () =>
        fail ? jsonResponse(409, { error: { code: 'IN_USE', message: 'has stock' } }) : new Response(null, { status: 204 }),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Delete p-TEC-001' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete product p-TEC-001?' })
    expect(within(dialog).getByText('DELETE /products/{id}')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('IN_USE: has stock')

    fail = false
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls('DELETE', '/api/products/p-TEC-001')).toHaveLength(2)
    expect(calls('GET', '/api/products')).toHaveLength(2)
  })

  it('cancelsADeleteWithoutCallingTheApi', async () => {
    const { calls } = routes({ 'GET /api/products': () => jsonResponse(200, products) })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Delete p-TEC-001' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls('DELETE', '/api/products/p-TEC-001')).toHaveLength(0)
  })

  it('rowActionsAreHiddenWhenTheEntityHasNoEndpoint', async () => {
    routes({
      'GET /api/restock/orders': () => jsonResponse(200, restockOrders),
      'GET /api/restock/receptions': () => jsonResponse(200, receptions),
    })
    renderPage('/domains/restock', 'Restock')
    const user = userEvent.setup()

    const row = (await screen.findByRole('cell', { name: 'ro-02072' })).closest('tr') as HTMLElement
    expect(within(row).queryAllByRole('button')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'New restock order' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Receptions' }))
    const reception = (await screen.findByRole('cell', { name: 'rec-02337' })).closest('tr') as HTMLElement
    expect(within(reception).getAllByRole('button').map((b) => b.textContent)).toEqual(['Edit'])
    expect(screen.getByRole('tab', { name: 'Receptions' })).toHaveAttribute('aria-selected', 'true')
  })

  it('scopesLinesToTheChosenZoneForListingAndCreating', async () => {
    const { calls } = routes({
      'GET /api/warehouse/zones': () => jsonResponse(200, zones),
      'GET /api/warehouse/zones/z-B/lines': () => jsonResponse(200, lines),
      'GET /api/warehouse/zones/6ad521e021cc8a6b3f2b69ca/lines': () => jsonResponse(200, { lines: [] }),
      'GET /api/v3/api-docs': SPEC,
      'POST /api/warehouse/zones/z-B/lines': () => jsonResponse(201, lines.lines[0]),
    })
    renderPage('/domains/warehouse', 'Warehouse')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Lines' }))
    expect(await screen.findByText('No lines yet.')).toBeInTheDocument()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Zone' }), 'z-B')
    expect(await screen.findByRole('cell', { name: 'l-B-1' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'New line' }))
    expect(await screen.findByText('POST /warehouse/zones/{zoneId}/lines')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('POST', '/api/warehouse/zones/z-B/lines')).toHaveLength(1))
  })

  it('saysSoWhenAScopeHasNoOptions', async () => {
    routes({ 'GET /api/warehouse/zones': () => jsonResponse(200, { zones: [] }) })
    renderPage('/domains/warehouse', 'Warehouse')

    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Lines' }))

    expect(await screen.findByText('Create a zone first.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New line' })).toBeNull()
  })

  it('reportsAFailedScopeLoad', async () => {
    routes({ 'GET /api/warehouse/zones': () => jsonResponse(500, { error: { code: 'INTERNAL', message: 'boom' } }) })
    renderPage('/domains/warehouse', 'Warehouse')

    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Positions' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('INTERNAL: boom')
  })

  describe('Files', () => {
    const filesRoute: Handler = () => jsonResponse(200, files)

    it('uploadModalHasAPathFieldAndAFileChooser', async () => {
      routes({ 'GET /api/api/v1/files': filesRoute })
      renderPage('/domains/files', 'Files')

      await userEvent.setup().click(await screen.findByRole('button', { name: 'New file' }))

      expect(screen.getByText('1 file')).toBeInTheDocument()
      const dialog = await screen.findByRole('dialog', { name: 'Upload file' })
      expect(within(dialog).getByRole('textbox', { name: 'Path' })).toHaveValue('images')
      expect(within(dialog).getByRole('textbox', { name: 'Path' })).toHaveAttribute('readonly')
      expect(within(dialog).getByLabelText('File')).toHaveAttribute('type', 'file')
      expect(within(dialog).getByLabelText('File')).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp')
      expect(within(dialog).getByText('POST /api/v1/files/upload')).toBeInTheDocument()
      expect(screen.queryByRole('textbox', { name: 'JSON body' })).toBeNull()
    })

    it('uploadSendsMultipartWithTheControllersPartNames', async () => {
      const { calls } = routes({
        'GET /api/api/v1/files': filesRoute,
        'POST /api/api/v1/files/upload': () => jsonResponse(200, { url: '/api/v1/files/images/x.png', key: 'images/x.png' }),
      })
      renderPage('/domains/files', 'Files')
      const user = userEvent.setup()
      const file = new File([new Uint8Array([0x89, 0x50])], 'dot.png', { type: 'image/png' })

      await user.click(await screen.findByRole('button', { name: 'New file' }))
      const dialog = await screen.findByRole('dialog', { name: 'Upload file' })
      expect(within(dialog).getByRole('button', { name: 'Upload' })).toBeDisabled()
      await user.upload(within(dialog).getByLabelText('File'), file)
      await user.click(within(dialog).getByRole('button', { name: 'Upload' }))

      await waitFor(() => expect(calls('POST', '/api/api/v1/files/upload')).toHaveLength(1))
      const body = calls('POST', '/api/api/v1/files/upload')[0][1]?.body as FormData
      expect([...body.keys()]).toEqual(['file'])
      expect(body.get('file')).toBe(file)
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(calls('GET', '/api/api/v1/files')).toHaveLength(2)
    })

    it('uploadShowsTheApiErrorInline', async () => {
      routes({
        'GET /api/api/v1/files': filesRoute,
        'POST /api/api/v1/files/upload': () =>
          jsonResponse(400, { error: { code: 'INVALID_REQUEST', message: 'Formato no soportado' } }),
      })
      renderPage('/domains/files', 'Files')
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: 'New file' }))
      const dialog = await screen.findByRole('dialog', { name: 'Upload file' })
      await user.upload(within(dialog).getByLabelText('File'), new File(['x'], 'a.png', { type: 'image/png' }))
      await user.click(within(dialog).getByRole('button', { name: 'Upload' }))

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('INVALID_REQUEST: Formato no soportado')
      expect(screen.getByRole('dialog', { name: 'Upload file' })).toBeInTheDocument()
    })

    it('downloadLinkUsesTheAuthenticatedFetch', async () => {
      const { calls } = routes({
        'GET /api/api/v1/files': filesRoute,
        [`GET ${fileUrl}`]: () => new Response('png', { status: 200, headers: { 'Content-Type': 'image/png' } }),
      })
      const createObjectURL = vi.fn(() => 'blob:console/1')
      const revokeObjectURL = vi.fn()
      Object.assign(URL, { createObjectURL, revokeObjectURL })
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
      renderPage('/domains/files', 'Files')

      await userEvent.setup().click(await screen.findByRole('button', { name: `Download ${fileRow.filename}` }))

      await waitFor(() => expect(click).toHaveBeenCalled())
      const [input, init] = calls('GET', fileUrl)[0]
      expect(String(input)).toBe(fileUrl)
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer tok')
      expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
      const anchor = click.mock.contexts[0] as HTMLAnchorElement
      expect(anchor.href).toBe('blob:console/1')
      expect(anchor.download).toBe(fileRow.filename)
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:console/1')
    })

    it('reportsAFailedDownload', async () => {
      routes({
        'GET /api/api/v1/files': filesRoute,
        [`GET ${fileUrl}`]: () => jsonResponse(404, { error: { code: 'FILE_NOT_FOUND', message: 'missing' } }),
      })
      renderPage('/domains/files', 'Files')

      await userEvent.setup().click(await screen.findByRole('button', { name: `Download ${fileRow.filename}` }))

      expect(await screen.findByRole('alert')).toHaveTextContent('FILE_NOT_FOUND: missing')
    })

    it('deletesAFileByPathAndFilename', async () => {
      const { calls } = routes({
        'GET /api/api/v1/files': filesRoute,
        [`DELETE ${fileUrl}`]: () => new Response(null, { status: 204 }),
      })
      renderPage('/domains/files', 'Files')
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: `Delete ${fileRow.filename}` }))
      await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))

      await waitFor(() => expect(calls('DELETE', fileUrl)).toHaveLength(1))
    })
  })
})
