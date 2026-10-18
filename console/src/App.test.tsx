import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { api, getSession, setSession } from './api/client'
import { mockFetch } from './test/http'

const renderApp = () =>
  render(
    <MemoryRouter>
      <App />
    </MemoryRouter>,
  )

describe('App', () => {
  beforeEach(() => setSession(null))

  it('asksForLoginWithoutASession', () => {
    renderApp()

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('showsTheShellForASessionAndSignsOut', async () => {
    setSession({ token: 't', email: 'root@x.io' })
    renderApp()

    expect(screen.getByText('root@x.io')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }))

    expect(getSession()).toBeNull()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('repromptsLoginWhenTheApiReports401', async () => {
    setSession({ token: 'expired', email: 'root@x.io' })
    renderApp()
    mockFetch(new Response(null, { status: 401 }))

    await act(() => api('/users').catch(() => undefined))

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired')
  })
})
