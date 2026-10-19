import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'
import files from './__fixtures__/files.json'
import lines from './__fixtures__/lines.json'
import positions from './__fixtures__/positions.json'
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
    expect(screen.queryByRole('button', { name: 'Live updates' })).toBeNull()
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
    expect(await screen.findByText(/^No products yet\./)).toBeInTheDocument()

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
    expect(within(dialog).getByText(/Prefilled with the structure of the request/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Create' })).toBeInTheDocument()
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
    await user.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls('POST', '/api/products')[0][1]?.body).toBe('{"sku":"X-1"}')
  })

  it('editOpensWithTheCurrentDocument', async () => {
    const current = { id: 'p-TEC-001', name: 'Fetched just now', active: false, stock: { available: 7, min: 3 } }
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/v3/api-docs': SPEC,
      'GET /api/products/p-TEC-001': () => jsonResponse(200, { product: current }),
    })
    renderPage('/domains/products', 'Products')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))

    const dialog = await screen.findByRole('dialog', { name: 'products / p-TEC-001' })
    expect(JSON.parse((editor() as HTMLTextAreaElement).value)).toEqual({ name: 'Fetched just now', is_active: false, minimum_stock: 3 })
    expect(within(dialog).getByText('PATCH /products/{id}')).toBeInTheDocument()
    expect(within(dialog).getByText(/Only fields this endpoint accepts are shown/)).toBeInTheDocument()
    expect(calls('GET', '/api/products/p-TEC-001')).toHaveLength(1)
  })

  it('editsFromTheRowWhenThereIsNoGetEndpoint', async () => {
    routes({ 'GET /api/warehouse/zones': () => jsonResponse(200, zones), 'GET /api/v3/api-docs': SPEC })
    renderPage('/domains/warehouse', 'Warehouse')

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit z-B' }))

    expect(JSON.parse((await screen.findByRole('textbox', { name: 'JSON body' }) as HTMLTextAreaElement).value)).toEqual({
      zone_code: 'B', max_allowed_lines: 10, is_active: true,
    })
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
    await user.click(screen.getByRole('button', { name: 'Send' }))

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
    await user.click(screen.getByRole('button', { name: 'Create' }))
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

  it('searchFiltersTheListAndStartsAtTheFirstPage', async () => {
    const { calls } = routes({
      'GET /api/products': (_init, url) => {
        const page = Number(new URL(url, 'http://console').searchParams.get('page'))
        return jsonResponse(200, { ...products, pagination: { page, size: 25, total_elements: 30, total_pages: 2 } })
      },
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), ' lenovo ')

    await waitFor(() =>
      expect(calls('GET', '/api/products').map(([input]) => String(input))).toContain('/api/products?page=0&size=25&search=lenovo'),
    )
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
  })

  it('hasNoSearchWhereTheListCannotSearch', async () => {
    routes({ 'GET /api/users': () => jsonResponse(200, users) })
    renderPage('/domains/users', 'Users')

    await screen.findByRole('cell', { name: 'u-system' })
    expect(screen.queryByRole('searchbox', { name: 'Search' })).toBeNull()
  })

  it('validateFitShowsTheResultAndKeepsTheModalOpen', async () => {
    const position = positions.positions[0]
    const fit = { fits: true, product_volume: 1, container_volume: 10, required_volume: 1, max_quantity_allowed: 10 }
    const { calls } = routes({
      'GET /api/warehouse/zones': () => jsonResponse(200, { zones: [zones.zones[1]] }),
      'GET /api/warehouse/zones/z-B/lines': () => jsonResponse(200, lines),
      'GET /api/warehouse/lines/l-B-1/positions': () => jsonResponse(200, positions),
      'GET /api/v3/api-docs': SPEC,
      'POST /api/warehouse/positions/validate-fit': () => jsonResponse(200, fit),
    })
    renderPage('/domains/warehouse', 'Warehouse')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Positions' }))
    await user.click(await screen.findByRole('button', { name: `Validate fit ${position.id_position}` }))
    const dialog = await screen.findByRole('dialog', { name: `Validate fit: ${position.id_position}` })
    expect(JSON.parse((editor() as HTMLTextAreaElement).value)).toEqual({
      product_id: position.product_id, quantity: 0, size: position.size_stock_to_save,
    })
    fireEvent.change(editor(), { target: { value: JSON.stringify({ product_id: position.product_id, quantity: 2, size: 'CAJA' }) } })
    await user.click(within(dialog).getByRole('button', { name: 'Run' }))

    const result = await within(dialog).findByRole('region', { name: 'Result' })
    expect(result).toHaveTextContent('"fits": true')
    expect(calls('POST', '/api/warehouse/positions/validate-fit')[0][1]?.body).toContain('"quantity":2')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(calls('GET', '/api/warehouse/lines/l-B-1/positions')).toHaveLength(1)
  })

  it('opensOnlyTheLatestEditWhenFetchesRace', async () => {
    const answers: ((r: Response) => void)[] = []
    routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/v3/api-docs': SPEC,
      'GET /api/products/p-TEC-001': () => new Promise<Response>((resolve) => answers.push(resolve)),
      'GET /api/products/p-TEC-002': () => jsonResponse(200, { product: { id: 'p-TEC-002', name: 'Second' } }),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))
    await user.click(screen.getByRole('button', { name: 'Edit p-TEC-002' }))
    expect(await screen.findByRole('dialog', { name: 'products / p-TEC-002' })).toBeInTheDocument()
    answers[0](jsonResponse(200, { product: { id: 'p-TEC-001', name: 'First' } }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.getByRole('dialog', { name: 'products / p-TEC-002' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'products / p-TEC-001' })).toBeNull()
  })

  it('aSlowEditNeverOpensOverALaterNewDialog', async () => {
    let answer: (r: Response) => void = () => {}
    routes({
      'GET /api/products': () => jsonResponse(200, products),
      'GET /api/v3/api-docs': SPEC,
      'GET /api/products/p-TEC-001': () => new Promise<Response>((resolve) => (answer = resolve)),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Edit p-TEC-001' }))
    await user.click(screen.getByRole('button', { name: 'New product' }))
    expect(await screen.findByRole('dialog', { name: 'New product' })).toBeInTheDocument()
    answer(jsonResponse(200, { product: { id: 'p-TEC-001', name: 'First' } }))

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.getByRole('dialog', { name: 'New product' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'products / p-TEC-001' })).toBeNull()
  })

  it('inactiveOnlyTogglesTheBackendsIsActiveFilterAndExplainsTheHiddenRows', async () => {
    const { calls } = routes({
      'GET /api/products': () => jsonResponse(200, { products: [], pagination: { page: 0, size: 25, total_elements: 0, total_pages: 0 } }),
    })
    renderPage('/domains/products', 'Products')
    const user = userEvent.setup()

    expect(await screen.findByText(/Inactive ones are hidden/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Inactive only' }))
    expect(await screen.findByText('No inactive products.')).toBeInTheDocument()
    expect(calls('GET', '/api/products').map(([input]) => String(input))).toEqual([
      '/api/products?page=0&size=25', '/api/products?page=0&size=25&isActive=false',
    ])
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
      expect(within(dialog).getByRole('textbox', { name: 'Path' })).not.toHaveAttribute('readonly')
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
      const path = within(dialog).getByRole('textbox', { name: 'Path' })
      await user.clear(path)
      await user.type(path, 'products/x')
      expect(within(dialog).getByText(/letters, digits, _ or - only/)).toBeInTheDocument()
      expect(within(dialog).getByRole('button', { name: 'Upload' })).toBeDisabled()
      await user.clear(path)
      await user.type(path, 'a'.repeat(65))
      expect(within(dialog).getByText(/1 to 64 characters/)).toBeInTheDocument()
      expect(within(dialog).getByRole('button', { name: 'Upload' })).toBeDisabled()
      await user.clear(path)
      await user.type(path, 'products')
      await user.click(within(dialog).getByRole('button', { name: 'Upload' }))

      await waitFor(() => expect(calls('POST', '/api/api/v1/files/upload')).toHaveLength(1))
      const body = calls('POST', '/api/api/v1/files/upload')[0][1]?.body as FormData
      expect([...body.keys()]).toEqual(['path', 'file'])
      expect(body.get('path')).toBe('products')
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
      await waitFor(() => expect(revokeObjectURL).toHaveBeenCalled())
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

    it('showsSizesHumanizedAndNoActionsForKeysTheEndpointsCannotAddress', async () => {
      routes({
        'GET /api/api/v1/files': () =>
          jsonResponse(200, { files: [fileRow, { path: '', filename: 'root.png', size: 2048 }, { path: 'a/b', filename: 'c.png', size: 1 }] }),
      })
      renderPage('/domains/files', 'Files')

      const root = (await screen.findByRole('cell', { name: 'root.png' })).closest('tr') as HTMLElement
      expect(within(root).queryAllByRole('button')).toHaveLength(0)
      expect(within(root).getByText('2.0 KB')).toBeInTheDocument()
      const nested = screen.getByRole('cell', { name: 'c.png' }).closest('tr') as HTMLElement
      expect(within(nested).queryAllByRole('button')).toHaveLength(0)
      expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Path', 'Filename', 'Size', 'Actions'])
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
