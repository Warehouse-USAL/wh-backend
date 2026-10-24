import { describe, expect, it } from 'vitest'
import { formatAge, formatBytes, formatUptime, percent } from './format'

describe('infra formats', () => {
  it('formatsBytesInDecimalUnits', () => {
    expect([512, 1500, 2.5e6, 1.84e9, 3e12].map(formatBytes)).toEqual(['512 B', '1.5 KB', '2.5 MB', '1.8 GB', '3 TB'])
  })

  it('formatsUptimeWithItsTwoLargestUnits', () => {
    expect([40_000, 300_000, 11_520_000, 187_200_000].map(formatUptime)).toEqual(['40s', '5m', '3h 12m', '2d 4h'])
  })

  it('formatsAgesCoarsely', () => {
    expect([-5, 20_400, 61_000, 7_300_000].map(formatAge)).toEqual(['0 s', '20 s', '1 min', '2 h'])
    expect(percent(12.345)).toBe('12.35%')
  })
})
