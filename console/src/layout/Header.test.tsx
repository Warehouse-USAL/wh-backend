import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { LiveProvider } from '@/data/live'
import Header from './Header'

const renderHeader = (props: Partial<Parameters<typeof Header>[0]> = {}) =>
  render(
    <LiveProvider>
      <Header crumb="Monitoring" title="Orders" {...props} />
    </LiveProvider>,
  )

describe('Header', () => {
  it('showsCrumbTitleChipsAndNoTabsByDefault', () => {
    renderHeader({ chips: ['env staging'] })

    expect(screen.getByRole('heading', { name: 'Orders' })).toBeInTheDocument()
    expect(screen.getByText('Monitoring /')).toBeInTheDocument()
    expect(screen.getByText('env staging')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Endpoints' })).toBeNull()
  })

  it('marksTheFirstTabThenTheControlledOne', async () => {
    const onTab = vi.fn()
    const { rerender } = renderHeader({ tabs: ['Endpoints', 'Live feed'], onTab })
    expect(screen.getByRole('tab', { name: 'Endpoints' })).toHaveAttribute('aria-selected', 'true')

    await userEvent.setup().click(screen.getByRole('tab', { name: 'Live feed' }))
    expect(onTab).toHaveBeenCalledWith('Live feed')

    rerender(
      <LiveProvider>
        <Header crumb="Monitoring" title="Orders" tabs={['Endpoints', 'Live feed']} tab="Live feed" onTab={onTab} />
      </LiveProvider>,
    )
    expect(screen.getByRole('tab', { name: 'Live feed' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Endpoints' }))
    expect(onTab).toHaveBeenLastCalledWith('Endpoints')
  })

  it('togglesLiveAndChangesTheRange', async () => {
    renderHeader()
    const user = userEvent.setup()
    const live = screen.getByRole('button', { name: 'Live updates' })
    expect(live).toHaveAttribute('aria-pressed', 'true')

    await user.click(live)
    expect(live).toHaveAttribute('aria-pressed', 'false')

    const range = screen.getByRole('combobox', { name: 'Time range' })
    expect(range).toHaveValue('15m')
    await user.selectOptions(range, '1h')
    expect(range).toHaveValue('1h')
  })

  it('showsActionsInsteadOfTheTimeControls', () => {
    render(<Header crumb="Domains" title="Users" actions={<button type="button">New user</button>} />)

    expect(screen.getByRole('button', { name: 'New user' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Live updates' })).toBeNull()
    expect(screen.queryByRole('combobox', { name: 'Time range' })).toBeNull()
  })
})
