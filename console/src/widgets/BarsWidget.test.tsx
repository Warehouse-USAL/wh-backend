import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import BarsWidget from './BarsWidget'
import type { Series } from './scale'

const at = (m: number) => new Date(2026, 0, 1, 13, m).getTime()
const long = (h: number) => new Date(2026, 0, 1, h).getTime()
const ok: Series = { label: '2xx', color: '#0070f3', points: [{ t: at(0), v: 3 }, { t: at(1), v: 6 }] }
const bad: Series = { label: '5xx', color: '#e5484d', points: [{ t: at(0), v: 2 }, { t: at(1), v: 1 }] }

describe('BarsWidget', () => {
  it('barsStackSeriesToTheTotal', () => {
    const { container } = render(<BarsWidget title="Hits" sub="by status" series={[ok, bad]} />)
    const rects = [...container.querySelectorAll('svg rect')]
    // max total 7 -> axis 10 (7 * 1.05 rounded up)
    expect(rects).toHaveLength(4)
    expect(rects[0]).toHaveAttribute('y', '70')
    expect(rects[0]).toHaveAttribute('height', '30')
    expect(rects[1]).toHaveAttribute('y', '50')
    expect(rects[1]).toHaveAttribute('height', '20')
    expect(rects[2]).toHaveAttribute('y', '40')
    expect(rects[3]).toHaveAttribute('y', '30')
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('13:00:00')).toBeInTheDocument()
    expect(screen.getByText('13:01:00')).toBeInTheDocument()
    expect(screen.getByRole('img')).toHaveAttribute('aria-label', 'Hits by status: 2xx 6, 5xx 1')
    expect(screen.getByText('2xx')).toBeInTheDocument()
    expect(screen.getByText('5xx')).toBeInTheDocument()
  })

  it('stacksByTimestampUnionWhenBucketsDiffer', () => {
    const late: Series = { label: '5xx', color: '#e5484d', points: [{ t: at(5), v: 4 }] }
    const { container } = render(<BarsWidget title="Hits" series={[ok, late]} />)
    expect(container.querySelectorAll('svg rect')).toHaveLength(3)
    expect(screen.getByText('13:05:00')).toBeInTheDocument()
    expect(screen.getByText('13:05:00 5xx 4', { selector: 'title' })).toBeInTheDocument()
  })

  it('emptySeriesShowsEmptyStateNotNaN', () => {
    const { container } = render(<BarsWidget title="Hits" series={[]} />)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeNull()
    expect(container).not.toHaveTextContent('NaN')
  })

  it('ignoresMissingNegativeAndNonFiniteValues', () => {
    const junk: Series = { label: 'x', color: '#000', points: [{ t: at(0), v: NaN }, { t: at(1), v: -3 }] }
    render(<BarsWidget title="Hits" series={[junk]} />)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
  })

  it('keepsAnSaneAxisForAllZeroSeries', () => {
    const zero: Series = { label: 'z', color: '#000', points: [{ t: at(0), v: 0 }] }
    const { container } = render(<BarsWidget title="Hits" series={[zero]} />)
    expect(container.querySelectorAll('svg rect')).toHaveLength(0)
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('0.5')).toBeInTheDocument()
    expect(container).not.toHaveTextContent('NaN')
  })

  it('labelsLongSpansWithoutSeconds', () => {
    const s: Series = { label: 'a', color: '#000', points: [{ t: long(1), v: 1 }, { t: long(3), v: 1 }] }
    render(<BarsWidget title="Hits" series={[s]} />)
    expect(screen.getByText('01:00')).toBeInTheDocument()
    expect(screen.getByText('03:00')).toBeInTheDocument()
  })

  it('formatsLargeAxisValues', () => {
    const big: Series = { label: 'b', color: '#000', points: [{ t: at(0), v: 3_100_000 }] }
    render(<BarsWidget title="Hits" series={[big]} />)
    expect(screen.getByText('5M')).toBeInTheDocument()
  })

  it('drawsATinyNonZeroSegmentAtAMinimumHeight', () => {
    const tiny: Series = { label: '5xx', color: '#e5484d', points: [{ t: at(0), v: 1 }] }
    const big: Series = { label: '2xx', color: '#0070f3', points: [{ t: at(0), v: 100000 }] }
    const { container } = render(<BarsWidget title="Hits" series={[big, tiny]} />)
    const rects = [...container.querySelectorAll('svg rect')]
    expect(rects[1]).toHaveAttribute('height', '1.5')
  })})
