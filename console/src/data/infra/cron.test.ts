import { describe, expect, it } from 'vitest'
import { nextDailyRun } from './cron'

const at = (iso: string) => Date.parse(iso)

describe('nextDailyRun', () => {
  it('picksTodayOrTomorrowAroundTheRunTime', () => {
    // Afternoon: today's 06:00 has passed, so tomorrow's.
    expect(nextDailyRun('0 6 * * *', at('2026-10-18T14:02:00Z'))).toBe(at('2026-10-19T06:00:00Z'))
    // Before the run: today.
    expect(nextDailyRun('0 6 * * *', at('2026-10-18T05:59:59Z'))).toBe(at('2026-10-18T06:00:00Z'))
    // Exactly at the run: it is firing now, the next one is tomorrow.
    expect(nextDailyRun('0 6 * * *', at('2026-10-18T06:00:00Z'))).toBe(at('2026-10-19T06:00:00Z'))
    // Month and year boundaries.
    expect(nextDailyRun('30 23 * * *', at('2026-12-31T23:45:00Z'))).toBe(at('2027-01-01T23:30:00Z'))
  })

  it('readsTheScheduleInTheGivenZoneOffset', () => {
    // 06:00 at UTC+2 is 04:00Z; at 03:00Z it is 05:00 there, so the run is an hour away.
    expect(nextDailyRun('0 6 * * *', at('2026-10-18T03:00:00Z'), 120)).toBe(at('2026-10-18T04:00:00Z'))
    // 06:00 at UTC-3 is 09:00Z; at 10:00Z (07:00 there) it has passed.
    expect(nextDailyRun('0 6 * * *', at('2026-10-18T10:00:00Z'), -180)).toBe(at('2026-10-19T09:00:00Z'))
    // A fixed offset has no DST: the UTC schedule is the same instant on both sides of a DST change.
    expect(nextDailyRun('0 6 * * *', at('2026-03-29T00:30:00Z'))).toBe(at('2026-03-29T06:00:00Z'))
    expect(nextDailyRun('0 6 * * *', at('2026-10-25T00:30:00Z'))).toBe(at('2026-10-25T06:00:00Z'))
  })

  it('answersOnlyTheDailyForm', () => {
    expect(nextDailyRun(' 5  6 * * * ', at('2026-10-18T00:00:00Z'))).toBe(at('2026-10-18T06:05:00Z'))
    for (const other of ['*/5 * * * *', '0 6 * * 1', '0 6,18 * * *', '60 6 * * *', '0 24 * * *', '', 'nonsense']) {
      expect(nextDailyRun(other, at('2026-10-18T00:00:00Z'))).toBeUndefined()
    }
  })
})
