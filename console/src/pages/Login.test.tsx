import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Login from './Login'
import { getSession, setSession } from '../api/client'
import { jsonResponse, jwt, mockFetch } from '../test/http'

async function signIn(email = 'root@x.io', password = 'pw') {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('Login', () => {
  beforeEach(() => setSession(null))

  it('loginScreenShowsTheServerErrorMessage', async () => {
    mockFetch(jsonResponse(401, { error: { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' } }))
    render(<Login />)

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(getSession()).toBeNull()
  })

  it('explainsWhyANonSuperadminIsRefused', async () => {
    mockFetch(jsonResponse(200, { token: jwt({ role: 'ADMIN_SYSTEM' }) }))
    render(<Login />)

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('SUPERADMIN')
  })

  it('reportsAnUnreachableServer', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))
    render(<Login />)

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server')
  })

  it.each([502, 503, 504])('reportsA%sFromTheProxyAsUnreachable', async (status) => {
    mockFetch(new Response('<html>Bad Gateway</html>', { status, statusText: 'Bad Gateway' }))
    render(<Login />)

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server')
  })

  it('showsAServerErrorMessageForOther5xx', async () => {
    mockFetch(jsonResponse(500, { error: { code: 'INTERNAL_ERROR', message: 'Unexpected error' } }))
    render(<Login />)

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unexpected error')
  })

  it('signsInASuperadmin', async () => {
    mockFetch(jsonResponse(200, { token: jwt({ email: 'root@x.io', role: 'SUPERADMIN' }) }))
    render(<Login />)

    await signIn()

    await vi.waitFor(() => expect(getSession()?.email).toBe('root@x.io'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('showsTheNoticeItIsGiven', () => {
    render(<Login notice="Your session expired. Sign in again." />)

    expect(screen.getByRole('status')).toHaveTextContent('Your session expired')
  })
})
