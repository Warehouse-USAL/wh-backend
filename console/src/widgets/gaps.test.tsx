import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import p95 from '@/api/__fixtures__/metrics-http-p95.json'
import { windowFor } from '@/data/useMetric'
import BarsWidget from './BarsWidget'
import LinesWidget from './LinesWidget'
import { type Series, formatTime } from './scale'

// The recorded p95 answer misses the 15 s buckets at ...910 and ...925: no request landed there.
const series: Series[] = [
  { label: 'p95', color: '#f5a623', points: p95.series[0].points.map(([t, v]) => ({ t: t * 1000, v })) },
]
const { times } = windowFor('15m', 1792356000_000)

describe('widgets on the window grid', () => {
  it('aBucketMissingFromEverySeriesBreaksTheLineAndTheAxisSpansTheWindow', () => {
    expect(times).toHaveLength(60)
    const { container } = render(<LinesWidget title="Latency" series={series} times={times} />)
    // 895 stands alone (a dot); 940-000 is one run.
    expect(container.querySelectorAll('polyline')).toHaveLength(1)
    expect(container.querySelector('polyline')?.getAttribute('points')?.split(' ')).toHaveLength(5)
    expect(container.querySelectorAll('path[stroke-linecap="round"]')).toHaveLength(1)
    expect(screen.getByText(formatTime(times[0], false))).toBeInTheDocument()
    expect(screen.getByText(formatTime(1792356000_000, false))).toBeInTheDocument()
  })

  it('aMissingBucketLeavesItsBarSlotEmpty', () => {
    const { container } = render(<BarsWidget title="Hits" series={series} times={times} />)
    // 60 slots of 5 units: points sit in slots 52 and 55-59; 53 and 54 stay empty.
    const xs = [...container.querySelectorAll('svg rect')].map((r) => r.getAttribute('x'))
    expect(xs).toEqual(['260.9', '275.9', '280.9', '285.9', '290.9', '295.9'])
  })

  it('withoutTimesTheUnionOfTimestampsIsUsedAsBefore', () => {
    const { container } = render(<LinesWidget title="Latency" series={series} />)
    expect(container.querySelector('polyline')?.getAttribute('points')?.split(' ')).toHaveLength(6)
    const bars = render(<BarsWidget title="Hits" series={series} />)
    expect([...bars.container.querySelectorAll('svg rect')].map((r) => r.getAttribute('x'))[0]).toBe('17')
  })

  it('aGridWithNoPointsIsStillTheEmptyState', () => {
    render(<LinesWidget title="Latency" series={[{ ...series[0], points: [] }]} times={times} />)
    expect(screen.getByText('No data in this range')).toBeInTheDocument()
  })
})
