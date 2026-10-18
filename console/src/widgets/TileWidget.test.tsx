import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import TileWidget from './TileWidget'
import { toneFor } from './scale'

describe('TileWidget', () => {
  it('tileToneFollowsThresholds', () => {
    const { rerender } = render(<TileWidget value="99%" label="Success rate" tone={toneFor(99)} />)
    expect(screen.getByText('99%')).toHaveClass('text-ok')
    rerender(<TileWidget value="96%" label="Success rate" tone={toneFor(96)} />)
    expect(screen.getByText('96%')).toHaveClass('text-wn')
    rerender(<TileWidget value="80%" label="Success rate" tone={toneFor(80)} />)
    expect(screen.getByText('80%')).toHaveClass('text-er')
    expect(screen.getByText('Success rate')).toBeInTheDocument()
  })

  it('drawsASparklineFromFiniteValuesOnly', () => {
    const { container } = render(<TileWidget value="1" label="x" tone="ok" spark={[1, NaN, 3, 2]} />)
    expect(container.querySelector('polyline')).toHaveAttribute('points', '0,34 50,6 100,20')
  })

  it('centersAFlatSparkline', () => {
    const { container } = render(<TileWidget value="1" label="x" tone="ok" spark={[4, 4]} />)
    expect(container.querySelector('polyline')).toHaveAttribute('points', '0,20 100,20')
  })

  it('omitsTheSparklineWithoutTwoValues', () => {
    const { container, rerender } = render(<TileWidget value="1" label="x" tone="ok" />)
    expect(container.querySelector('svg')).toBeNull()
    rerender(<TileWidget value="1" label="x" tone="ok" spark={[3]} />)
    expect(container.querySelector('svg')).toBeNull()
  })
})
