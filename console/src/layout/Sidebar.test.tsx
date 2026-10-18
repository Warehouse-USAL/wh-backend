import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { setTheme } from '@/lib/theme'
import Sidebar from './Sidebar'

// The Dev tools section shows only under the dev profile (hidden case: pages/DevTool.test.tsx).
vi.mock('@/data/streamHooks', () => ({ useStatus: () => ({ data: { services: [], dev: true }, loading: false }) }))

const renderAt = (path: string, onSignOut = vi.fn()) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Sidebar onSignOut={onSignOut} />
    </MemoryRouter>,
  )

describe('Sidebar', () => {
  beforeEach(() => {
    setSession({ token: 't', email: 'root@x.io' })
    setTheme('system')
  })

  it('sidebarMarksTheActiveRoute', () => {
    renderAt('/infra/mongodb')

    const active = screen.getAllByRole('link').filter((l) => l.getAttribute('aria-current') === 'page')
    expect(active).toHaveLength(1)
    expect(active[0]).toHaveTextContent('MongoDB')
    expect(screen.getByRole('link', { name: 'Users' })).not.toHaveAttribute('aria-current')
  })

  it('sidebarToggleSwitchesTheme', async () => {
    renderAt('/overview')
    const user = userEvent.setup()
    const group = screen.getByRole('group', { name: 'Theme' })
    expect(within(group).getByRole('button', { name: 'System' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(within(group).getByRole('button', { name: 'Dark' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(within(group).getByRole('button', { name: 'Dark' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(group).getByRole('button', { name: 'System' })).toHaveAttribute('aria-pressed', 'false')

    await user.click(within(group).getByRole('button', { name: 'Light' }))
    expect(document.documentElement.dataset.theme).toBe('light')
    await user.click(within(group).getByRole('button', { name: 'System' }))
    expect(document.documentElement).not.toHaveAttribute('data-theme')
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
