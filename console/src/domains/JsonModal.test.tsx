import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import JsonModal from './JsonModal'

const renderModal = (props: Partial<Parameters<typeof JsonModal>[0]> = {}) => {
  const onSubmit = vi.fn<(body: unknown) => Promise<void>>().mockResolvedValue(undefined)
  const onClose = vi.fn()
  render(
    <JsonModal
      title="New product"
      initial={{ name: '', stock: 0 }}
      endpointLabel="POST /products"
      onSubmit={onSubmit}
      onClose={onClose}
      {...props}
    />,
  )
  return { onSubmit, onClose, editor: screen.getByRole('textbox', { name: 'JSON body' }) }
}

describe('JsonModal', () => {
  it('showsTheTitleEndpointAndNumberedJson', () => {
    const { editor } = renderModal()

    expect(screen.getByRole('dialog', { name: 'New product' })).toBeInTheDocument()
    expect(screen.getByText('POST /products')).toBeInTheDocument()
    expect(editor).toHaveValue('{\n  "name": "",\n  "stock": 0\n}')
    expect(screen.getByTestId('gutter')).toHaveTextContent('1234')
  })

  it('modalRefusesInvalidJsonAndSendsNothing', async () => {
    const { onSubmit, editor } = renderModal()
    fireEvent.change(editor, { target: { value: '{\n  "name": "x",\n}' } })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByRole('alert')).toHaveTextContent(/^Invalid JSON: .*line 3 column 1/)
    expect(onSubmit).not.toHaveBeenCalled()
    expect(editor).toHaveValue('{\n  "name": "x",\n}')
    expect(screen.getByTestId('gutter')).toHaveTextContent('123')
  })

  it('derivesTheLineFromAPositionOnlyMessage', async () => {
    const parse = vi.spyOn(JSON, 'parse').mockImplementation(() => {
      throw new SyntaxError('Unexpected token in JSON at position 5')
    })
    const { editor } = renderModal()
    fireEvent.change(editor, { target: { value: '{\n "a"' } })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Invalid JSON: Unexpected token in JSON at position 5 (line 2, column 4)')
    parse.mockRestore()
  })

  it('dropsTheEchoedTextFromAnUnexpectedTokenMessage', async () => {
    const { editor } = renderModal()
    fireEvent.change(editor, { target: { value: '{"name": }' } })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByRole('alert')).toHaveTextContent(/^Invalid JSON: Unexpected token '}'$/)
  })

  it('sendsTheParsedBody', async () => {
    const { onSubmit, editor } = renderModal()
    fireEvent.change(editor, { target: { value: '{"name": "Cart", "stock": 3}' } })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(onSubmit).toHaveBeenCalledWith({ name: 'Cart', stock: 3 })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('modalShowsTheApiErrorInline', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new ApiError(422, 'INVALID_REQUEST', 'sku must not be blank'))
    const { onClose } = renderModal({ onSubmit })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_REQUEST: sku must not be blank')
    expect(screen.getByRole('dialog', { name: 'New product' })).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('showsOtherFailuresInlineToo', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    renderModal({ onSubmit })

    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to fetch')
  })

  it('closesOnCancelAndEscape', async () => {
    const { onClose } = renderModal()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    await user.keyboard('{Escape}')
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(2))
  })
})
