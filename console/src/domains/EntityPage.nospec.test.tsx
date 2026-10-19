import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getSession, setSession } from '@/api/client'
import { jsonResponse } from '@/test/http'
import users from './__fixtures__/users.json'
import { DOMAIN_PAGES } from './entities'
import EntityPage from './EntityPage'

// Its own file so the springdoc cache starts empty: the backend serves the document at neither path.
describe('EntityPage without a schema', () => {
  beforeEach(() => {
    setSession({ token: 'tok', email: 'root@x.io' })
    const user = users.users[1]
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input) => {
        const url = String(input).split('?')[0]
        if (url === '/api/users') return jsonResponse(200, users)
        if (url === `/api/users/${user.id}`) return jsonResponse(200, user)
        return new Response(null, { status: 401 })
      }),
    )
  })

  it('opensNewAndExtrasWithAnEmptyTemplateAndANoteWithoutSigningOut', async () => {
    render(<EntityPage title="Users" entities={DOMAIN_PAGES['/domains/users']} />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'New user' }))
    const dialog = await screen.findByRole('dialog', { name: 'New user' })
    expect(within(dialog).getByRole('textbox', { name: 'JSON body' })).toHaveValue('{}')
    expect(within(dialog).getByText('Schema unavailable, template empty.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await user.click(screen.getByRole('button', { name: 'Reset password u-system' }))
    expect(await screen.findByText('Schema unavailable, template empty.')).toBeInTheDocument()
    expect(getSession()).not.toBeNull()
  })

  it('editShowsTheWholeDocumentWithANote', async () => {
    render(<EntityPage title="Users" entities={DOMAIN_PAGES['/domains/users']} />)

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Edit u-system' }))

    const dialog = await screen.findByRole('dialog', { name: 'users / u-system' })
    expect(JSON.parse((within(dialog).getByRole('textbox', { name: 'JSON body' }) as HTMLTextAreaElement).value)).toEqual(users.users[1])
    expect(within(dialog).getByText('Schema unavailable, showing the whole document.')).toBeInTheDocument()
  })
})
