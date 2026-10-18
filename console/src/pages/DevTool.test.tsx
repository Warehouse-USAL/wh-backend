import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import { useStatus } from '@/data/streamHooks'
import Sidebar from '@/layout/Sidebar'
import { LiveProvider } from '@/data/live'
import DevTool from './DevTool'

vi.mock('@/data/streamHooks', () => ({ useStatus: vi.fn(), useLogs: vi.fn(), useWsEvents: vi.fn() }))

const status = (dev: boolean, loading = false) => vi.mocked(useStatus).mockReturnValue({ data: { services: [], dev }, loading })

const renderTool = (name = 'mongo-express', port = 8091) =>
  render(
    <LiveProvider>
      <DevTool name={name} port={port} />
    </LiveProvider>,
  )

describe('DevTool', () => {
  beforeEach(() => setSession({ token: 't', email: 'root@x.io' }))

  it('devToolFrameLoadsThroughTheProxyPort', () => {
    status(true)
    renderTool()

    const origin = `${location.protocol}//${location.hostname}:8091`
    const frame = screen.getByTitle('mongo-express')
    expect(frame.tagName).toBe('IFRAME')
    expect(frame).toHaveAttribute('src', `${origin}/`)
    const sandbox = frame.getAttribute('sandbox')?.split(' ') ?? []
    expect(sandbox).toEqual(expect.arrayContaining(['allow-scripts', 'allow-same-origin', 'allow-forms']))
    expect(sandbox).not.toContain('allow-top-navigation')
    expect(frame).toHaveAttribute('referrerpolicy', 'no-referrer')

    const open = screen.getByRole('link', { name: 'Open in new tab' })
    expect(open).toHaveAttribute('href', `${origin}/`)
    expect(open).toHaveAttribute('target', '_blank')
    expect(open.getAttribute('rel')).toContain('noopener')
    expect(screen.getByRole('heading', { name: 'mongo-express' })).toBeInTheDocument()
    expect(screen.getByText('Dev tools /')).toBeInTheDocument()
  })

  it('usesTheGivenPortForRedpandaConsole', () => {
    status(true)
    renderTool('redpanda-console', 8092)

    expect(screen.getByTitle('redpanda-console')).toHaveAttribute('src', `${location.protocol}//${location.hostname}:8092/`)
  })

  it('explainsWhenTheBackendIsNotInDev', () => {
    status(false)
    renderTool()

    expect(screen.queryByTitle('mongo-express')).toBeNull()
    expect(screen.getByText(/only under the dev compose profile/)).toBeInTheDocument()
  })

  it('waitsForTheStatusBeforeDeciding', () => {
    status(false, true)
    renderTool()

    expect(screen.queryByTitle('mongo-express')).toBeNull()
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument()
  })

  it('devToolsHiddenWhenNotDev', () => {
    status(false)
    const { unmount } = render(
      <MemoryRouter>
        <Sidebar onSignOut={vi.fn()} />
      </MemoryRouter>,
    )
    expect(screen.queryByText('Dev tools')).toBeNull()
    expect(screen.queryByRole('link', { name: 'mongo-express' })).toBeNull()
    unmount()

    status(true)
    render(
      <MemoryRouter>
        <Sidebar onSignOut={vi.fn()} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Dev tools')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'redpanda-console' })).toHaveAttribute('href', '/dev/redpanda-console')
  })
})
