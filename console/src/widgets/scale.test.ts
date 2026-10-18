import { describe, expect, it } from 'vitest'
import { formatCount, formatMs, niceMax, plotData, timeLabels, toneFor } from './scale'

const at = (h: number, m: number, s = 0) => new Date(2026, 0, 1, h, m, s).getTime()

describe('scale', () => {
  it('niceMaxRoundsUpTo125', () => {
    expect(niceMax(0.7)).toBe(1)
    expect(niceMax(130)).toBe(200)
    expect(niceMax(420)).toBe(500)
    expect(niceMax(0.03)).toBe(0.05)
    expect(niceMax(2000)).toBe(2000)
  })

  it('niceMaxFallsBackToOneForZeroNegativeAndNonFinite', () => {
    for (const v of [0, -5, NaN, Infinity]) expect(niceMax(v)).toBe(1)
  })

  it('formatMsSwitchesToSecondsAtOneThousand', () => {
    expect(formatMs(999)).toBe('999 ms')
    expect(formatMs(1000)).toBe('1 s')
    expect(formatMs(2500)).toBe('2.5 s')
    expect(formatMs(0.5)).toBe('0.5 ms')
  })

  it('formatCountAbbreviatesThousandsAndMillions', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(12.345)).toBe('12.35')
    expect(formatCount(999)).toBe('999')
    expect(formatCount(1200)).toBe('1.2k')
    expect(formatCount(3_400_000)).toBe('3.4M')
    expect(formatCount(999_999)).toBe('1M')
  })

  it('toneForFollowsThresholds', () => {
    expect(toneFor(98)).toBe('ok')
    expect(toneFor(97.9)).toBe('warn')
    expect(toneFor(95)).toBe('warn')
    expect(toneFor(94.9)).toBe('error')
  })

  it('plotDataUnionsTimestampsAndDropsInvalidPoints', () => {
    const data = plotData([
      { label: 'a', color: 'x', points: [{ t: 2, v: 1 }, { t: 1, v: 2 }, { t: 3, v: NaN }, { t: 4, v: -1 }] },
      { label: 'b', color: 'y', points: [{ t: 2, v: 5 }, { t: 5, v: Infinity }] },
    ])
    expect(data.times).toEqual([1, 2])
    expect(data.values).toEqual([[2, 1], [undefined, 5]])
  })

  it('plotDataLaysSeriesOntoAGivenGrid', () => {
    const data = plotData([{ label: 'a', color: 'x', points: [{ t: 1, v: 2 }, { t: 3, v: 4 }, { t: 9, v: 9 }] }], [1, 2, 3])
    expect(data).toEqual({ times: [1, 2, 3], values: [[2, undefined, 4]] })
  })

  it('timeLabelsAreEvenlySpacedAndShowSecondsOnShortSpans', () => {
    const long = Array.from({ length: 10 }, (_, i) => at(13, 48 + i * 2))
    expect(timeLabels(long)).toEqual(['13:48', '13:52', '13:58', '14:02', '14:06'])
    expect(timeLabels([at(10, 0, 5), at(10, 0, 20)])).toEqual(['10:00:05', '10:00:20'])
    expect(timeLabels([at(9, 3)])).toEqual(['09:03:00'])
    expect(timeLabels([])).toEqual([])
  })
})
