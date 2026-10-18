import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import Sidebar from './Sidebar'

const renderAt = (path: string, onSignOut = vi.fn()) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Sidebar onSignOut={onSignOut} />
    </MemoryRouter>,
  )

describe('Sidebar', () => {
  beforeEach(() => setSession({ token: 't', email: 'root@x.io' }))

  it('sidebarMarksTheActiveRoute', () => {
    renderAt('/infra/mongodb')

    const active = screen.getAllByRole('link').filter((l) => l.getAttribute('aria-current') === 'page')
    expect(active).toHaveLength(1)
    expect(active[0]).toHaveTextContent('MongoDB')
    expect(screen.getByRole('link', { name: 'Users' })).not.toHaveAttribute('aria-current')
  })

  it('showsSectionLabelsAndTheUserAndSignsOut', async () => {
    const onSignOut = vi.fn()
    renderAt('/overview', onSignOut)

    expect(screen.getByText('wh-backend')).toBeInTheDocument()
    expect(screen.queryByText('Overview', { selector: 'div' })).toBeNull()
    expect(screen.getByText('Dev tools')).toBeInTheDocument()
    expect(screen.getByText('root@x.io')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }))
    expect(onSignOut).toHaveBeenCalledOnce()
  })
})
