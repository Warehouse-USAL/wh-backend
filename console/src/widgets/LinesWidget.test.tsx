import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import LinesWidget from './LinesWidget'
import { formatMs, type Series } from './scale'

const at = (m: number) => new Date(2026, 0, 1, 13, m).getTime()
const pts = (vs: (number | null)[]) =>
  vs.flatMap((v, i) => (v === null ? [] : [{ t: at(i), v }]))

describe('LinesWidget', () => {
  it('drawsOnePolylinePerContiguousRunSoGapsBreakTheLine', () => {
    const s: Series = { label: 'p50', color: '#a1a1a1', points: pts([10, 20, null, 30, 40, 50]) }
    const filler: Series = { label: 'p99', color: '#e5484d', points: pts([1, 1, 1, 1, 1, 1]) }
    const { container } = render(<LinesWidget title="Latency" sub="p50" series={[s, filler]} format={formatMs} />)
    const lines = container.querySelectorAll('polyline')
    expect(lines).toHaveLength(3)
    expect(lines[0]).toHaveAttribute('points', '0,90 60,80')
    expect(lines[1]).toHaveAttribute('points', '180,70 240,60 300,50')
    expect(screen.getByText('100 ms')).toBeInTheDocument()
    expect(screen.getByText('50 ms')).toBeInTheDocument()
  })

  it('rendersASinglePointAsADotNotNaN', () => {
    const s: Series = { label: 'p50', color: '#0070f3', points: pts([5]) }
    const { container } = render(<LinesWidget title="Latency" series={[s]} />)
    expect(container.querySelector('polyline')).toBeNull()
    expect(container.querySelector('svg path[stroke-linecap="round"]')).toHaveAttribute('d', 'M150,50h0')
    expect(container.innerHTML).not.toContain('NaN')
  })

  it('drawsAnAreaUnderTheLineWhenAsked', () => {
    const s: Series = { label: 'err', color: '#e5484d', points: pts([1, 2]) }
    const { container } = render(<LinesWidget title="Error rate" series={[s]} area />)
    expect(container.querySelector('path[fill-opacity="0.12"]')).toHaveAttribute('d', 'M0,100L0,80L300,60L300,100Z')
  })

  it('emptySeriesShowsEmptyStateNotNaN', () => {
    const s: Series = { label: 'p50', color: '#0070f3', points: [{ t: at(0), v: NaN }] }
    const { container } = render(<LinesWidget title="Latency" series={[s]} />)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
    expect(container).not.toHaveTextContent('NaN')
  })

  it('describesTheLatestValuesToAssistiveTech', () => {
    const s: Series = { label: 'p50', color: '#0070f3', points: pts([5, 7]) }
    render(<LinesWidget title="Latency" series={[s]} />)
    expect(screen.getByRole('img')).toHaveAttribute('aria-label', 'Latency: p50 7')
  })
})
