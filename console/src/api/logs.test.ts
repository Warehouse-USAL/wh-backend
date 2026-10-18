import { describe, expect, it } from 'vitest'
import fixture from './__fixtures__/logs-query.json'
import { logsParams, toLogLine } from './logs'

describe('logs wire mapping', () => {
  it('mapsTheSnakeCaseLine', () => {
    expect(toLogLine(fixture.lines[0])).toEqual({
      time: '2026-10-18T21:23:22.539927500Z',
      level: 'info',
      service: 'bridge',
      message: 'order published to infra',
      requestId: 't23order1',
    })
    expect(toLogLine(fixture.lines[3]).requestId).toBeNull()
  })

  it('sendsTheFiltersSnakeCaseAndLeavesOutTheOnesNotGiven', () => {
    expect(logsParams({ service: 'bridge', level: 'warn', requestId: 't23order1', text: 'retry', limit: 50 })).toEqual({
      service: 'bridge',
      level: 'warn',
      request_id: 't23order1',
      text: 'retry',
      limit: 50,
    })
    expect(JSON.stringify(logsParams({}))).toBe('{}')
  })
})
