import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Group from './Group'

describe('Group', () => {
  it('rendersHeaderAndChildrenInATwelveColumnGrid', () => {
    render(
      <Group title="GET /orders" tag="list orders" right={<span>JWT</span>}>
        <div className="col-span-4">child</div>
      </Group>,
    )
    const region = screen.getByRole('region', { name: 'GET /orders' })
    expect(region).toHaveTextContent('list orders')
    expect(region).toHaveTextContent('JWT')
    expect(screen.getByText('child').parentElement).toHaveClass('grid-cols-12')
  })

  it('omitsOptionalHeaderParts', () => {
    render(<Group title="Overview"><i>x</i></Group>)
    expect(screen.getByRole('region', { name: 'Overview' }).querySelectorAll('span')).toHaveLength(0)
  })
})
