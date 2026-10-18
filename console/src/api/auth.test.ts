import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { login, useSession } from './auth'
import { getSession, setSession } from './client'
import { jsonResponse, jwt, mockFetch } from '../test/http'

const loginResponse = (token: string) =>
  jsonResponse(200, { token, user: { id: 'u1', name: 'Root', email: 'root@x.io', role: 'IGNORED' } })

describe('login', () => {
  beforeEach(() => setSession(null))

  it('loginRejectsAdminSystemAndStoresNothing', async () => {
    mockFetch(loginResponse(jwt({ sub: 'u2', email: 'ops@x.io', role: 'ADMIN_SYSTEM' })))

    await expect(login('ops@x.io', 'pw')).rejects.toMatchObject({ status: 403, code: 'NOT_SUPERADMIN' })

    expect(getSession()).toBeNull()
    expect(sessionStorage.length).toBe(0)
  })

  it('loginRejectsATokenItCannotRead', async () => {
    mockFetch(loginResponse('not-a-jwt'))

    await expect(login('root@x.io', 'pw')).rejects.toMatchObject({ code: 'NOT_SUPERADMIN' })
    expect(getSession()).toBeNull()
  })

  it('loginRejectsAResponseWithoutToken', async () => {
    mockFetch(jsonResponse(200, {}))

    await expect(login('root@x.io', 'pw')).rejects.toMatchObject({ code: 'NOT_SUPERADMIN' })
    expect(getSession()).toBeNull()
  })

  it('loginStoresSuperadminToken', async () => {
    const token = jwt({ sub: 'u1', email: 'root@x.io', role: 'SUPERADMIN' })
    const fetchMock = mockFetch(loginResponse(token))

    await login('ROOT@x.io', 'secret')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/auth/login')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ROOT@x.io', password: 'secret' })
    expect(getSession()).toEqual({ token, email: 'root@x.io' })
    expect(sessionStorage.getItem('wh-console.session')).toContain(token)
  })

  it('fallsBackToTheTypedEmailWhenTheTokenHasNone', async () => {
    mockFetch(loginResponse(jwt({ sub: 'u1', role: 'SUPERADMIN' })))

    await login('root@x.io', 'secret')

    expect(getSession()?.email).toBe('root@x.io')
  })
})

describe('useSession', () => {
  beforeEach(() => setSession(null))

  it('followsTheSessionAndLogsOut', () => {
    const { result } = renderHook(() => useSession())
    expect(result.current).toMatchObject({ token: null, email: null })

    act(() => setSession({ token: 't', email: 'root@x.io' }))
    expect(result.current).toMatchObject({ token: 't', email: 'root@x.io' })

    act(() => result.current.logout())
    expect(result.current.token).toBeNull()
    expect(getSession()).toBeNull()
  })
})
