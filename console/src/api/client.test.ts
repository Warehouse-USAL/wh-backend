import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, apiBlob, errorText, getSession, onUnauthorized, setSession } from './client'
import { jsonResponse, mockFetch } from '../test/http'

describe('api', () => {
  beforeEach(() => setSession(null))

  it('apiAddsBearerAndPrefix', async () => {
    setSession({ token: 'tok-1', email: 'root@x.io' })
    const fetchMock = mockFetch(jsonResponse(200, { ok: true }))

    const body = await api('/products?page=0', { method: 'POST', body: '{}' })

    expect(body).toEqual({ ok: true })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/products?page=0')
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer tok-1')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init?.method).toBe('POST')
  })

  it('sendsNoAuthorizationWithoutSession', async () => {
    const fetchMock = mockFetch(new Response(null, { status: 204 }))

    expect(await api('/health')).toBeNull()
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).has('Authorization')).toBe(false)
  })

  it('apiTurnsErrorBodyIntoApiError', async () => {
    mockFetch(jsonResponse(422, { error: { code: 'VALIDATION_ERROR', message: 'name is required' } }))

    const error = await api('/products').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 422, code: 'VALIDATION_ERROR', message: 'name is required' })
  })

  it('apiTurnsANonStandardErrorIntoApiError', async () => {
    mockFetch(new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' }))

    await expect(api('/products')).rejects.toMatchObject({ status: 502, code: 'HTTP_502', message: 'Bad Gateway' })
  })

  it('401ClearsSessionAndCallsHandler', async () => {
    setSession({ token: 'expired', email: 'root@x.io' })
    const handler = vi.fn()
    const unsubscribe = onUnauthorized(handler)
    mockFetch(new Response(null, { status: 401 }))

    await expect(api('/users')).rejects.toMatchObject({ status: 401 })

    expect(getSession()).toBeNull()
    expect(sessionStorage.length).toBe(0)
    expect(handler).toHaveBeenCalledOnce()
    unsubscribe()
  })

  it('stale401KeepsANewerSession', async () => {
    setSession({ token: 'old', email: 'root@x.io' })
    const handler = vi.fn()
    const unsubscribe = onUnauthorized(handler)
    let answer: (response: Response) => void = () => undefined
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(() => new Promise((resolve) => (answer = resolve))))

    const pending = api('/users')
    setSession({ token: 'fresh', email: 'root@x.io' })
    answer(new Response(null, { status: 401 }))

    await expect(pending).rejects.toMatchObject({ status: 401 })
    expect(getSession()).toEqual({ token: 'fresh', email: 'root@x.io' })
    expect(handler).not.toHaveBeenCalled()
    unsubscribe()
  })

  it('401WithoutSessionDoesNotCallHandler', async () => {
    const handler = vi.fn()
    const unsubscribe = onUnauthorized(handler)
    mockFetch(jsonResponse(401, { error: { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' } }))

    await expect(api('/auth/login')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })

    expect(handler).not.toHaveBeenCalled()
    unsubscribe()
  })
})

describe('apiBlob', () => {
  beforeEach(() => setSession({ token: 'tok', email: 'root@x.io' }))

  it('downloadsWithTheTokenInTheHeader', async () => {
    const fetchMock = mockFetch(new Response('png', { status: 200 }))

    const blob = await apiBlob('/api/v1/files/images/a.png')

    expect(await blob.text()).toBe('png')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/api/v1/files/images/a.png')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer tok')
  })

  it('turnsAFailedDownloadIntoApiError', async () => {
    mockFetch(jsonResponse(404, { error: { code: 'FILE_NOT_FOUND', message: 'missing' } }))

    await expect(apiBlob('/api/v1/files/images/a.png')).rejects.toMatchObject({ status: 404, code: 'FILE_NOT_FOUND' })
  })
})

describe('errorText', () => {
  it('prefersTheApiCodeThenTheMessage', () => {
    expect(errorText(new ApiError(422, 'INVALID_REQUEST', 'bad'))).toBe('INVALID_REQUEST: bad')
    expect(errorText(new TypeError('Failed to fetch'))).toBe('Failed to fetch')
    expect(errorText('plain')).toBe('plain')
  })
})

describe('session', () => {
  beforeEach(() => setSession(null))

  it('persistsToSessionStorageAndRestoresOnReload', async () => {
    setSession({ token: 'tok-2', email: 'root@x.io' })
    vi.resetModules()

    const reloaded = await import('./client')

    expect(reloaded.getSession()).toEqual({ token: 'tok-2', email: 'root@x.io' })
  })

  it('ignoresACorruptStoredSession', async () => {
    sessionStorage.setItem('wh-console.session', '{"token":42}')
    vi.resetModules()

    const reloaded = await import('./client')

    expect(reloaded.getSession()).toBeNull()
  })

  it('keepsWorkingInMemoryWhenStorageIsUnavailable', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })

    setSession({ token: 'tok-3', email: 'root@x.io' })

    expect(getSession()).toEqual({ token: 'tok-3', email: 'root@x.io' })
  })
})
