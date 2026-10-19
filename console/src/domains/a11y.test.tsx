import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setSession } from '@/api/client'
import ThemeToggle from '@/layout/ThemeToggle'
import { jsonResponse } from '@/test/http'
import products from './__fixtures__/products.json'
import { DOMAIN_PAGES } from './entities'
import EntityPage from './EntityPage'

describe('accessibility', () => {
  beforeEach(() => {
    setSession({ token: 'tok', email: 'root@x.io' })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => jsonResponse(200, products)))
  })

  it('theEntityTableIsNamedAndEveryButtonAndFieldHasAnAccessibleName', async () => {
    render(<EntityPage title="Products" entities={DOMAIN_PAGES['/domains/products']} />)
    await screen.findByRole('cell', { name: 'p-TEC-001' })

    expect(screen.getByRole('table', { name: 'Products' })).toBeInTheDocument()
    for (const button of screen.getAllByRole('button')) expect(button, button.outerHTML).toHaveAccessibleName()
    for (const field of [...screen.queryAllByRole('textbox'), ...screen.getAllByRole('checkbox'), ...screen.queryAllByRole('searchbox')]) {
      expect(field, field.outerHTML).toHaveAccessibleName()
    }
  })

  it('theIconOnlyThemeButtonsAreNamedAndReportTheirPressedState', () => {
    render(<ThemeToggle />)
    for (const name of ['Light', 'Dark', 'System']) expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed')
  })

  it('aDialogTakesFocusInsideAndHandsItBackOnEscape', async () => {
    render(<EntityPage title="Products" entities={DOMAIN_PAGES['/domains/products']} />)
    const user = userEvent.setup()
    const trigger = await screen.findByRole('button', { name: 'Delete p-TEC-001' })
    await user.click(trigger)
    const dialog = await screen.findByRole('dialog')

    // The Tab loop itself (Base UI focus guards) needs a real browser; jsdom has no such redirect.
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
    expect(dialog).toHaveAccessibleName('Delete product p-TEC-001?')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(trigger).toHaveFocus())
  })
})
