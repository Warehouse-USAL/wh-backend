import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import TopListWidget from './TopListWidget'

const fmt = (v: number) => `${v} req`

describe('TopListWidget', () => {
  it('sizesEachBarRelativeToTheLargestValue', () => {
    const { container } = render(
      <TopListWidget title="Requests" sub="per min" color="#0070f3" format={fmt}
        rows={[{ name: 'GET /a', value: 200 }, { name: 'GET /b', value: 50 }, { name: 'GET /c', value: 0 }]} />,
    )
    const bars = [...container.querySelectorAll('i')].map((i) => i.style.width)
    expect(bars).toEqual(['100%', '25%', '1.5%'])
    expect(screen.getByText('GET /a')).toBeInTheDocument()
    expect(screen.getByText('200 req')).toBeInTheDocument()
  })

  it('ignoresInvalidRowsAndShowsEmptyStateWhenNoneLeft', () => {
    render(
      <TopListWidget title="Requests" color="#000" format={fmt}
        rows={[{ name: 'x', value: NaN }, { name: 'y', value: -1 }]} />,
    )
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
  })

  it('keysRowsWithTheSameNameApart', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<TopListWidget title="Requests" color="#000" format={fmt} rows={[{ name: 'x', value: 2 }, { name: 'x', value: 1 }]} />)
    expect(screen.getAllByText('x')).toHaveLength(2)
    expect(error).not.toHaveBeenCalled()
  })})
