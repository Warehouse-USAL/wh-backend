import { describe, expect, it } from 'vitest'
import { jsonResponse, mockFetch } from '@/test/http'
import fixture from './__fixtures__/logs-query.json'
import { ApiError } from './client'
import { queryLogs, trailFor } from './logs'

describe('queryLogs', () => {
  it('sendsTheSnakeCaseBodyAndMapsTheLines', async () => {
    const fetchMock = mockFetch(jsonResponse(200, fixture))
    const from = new Date('2026-10-18T18:00:00Z')
    const to = new Date('2026-10-18T19:00:00Z')
    const result = await queryLogs({ service: 'bridge', level: 'warn', requestId: 't23order1', text: 'retry', from, to, limit: 50 })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/console/logs/query')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      service: 'bridge',
      level: 'warn',
      request_id: 't23order1',
      text: 'retry',
      from: '2026-10-18T18:00:00.000Z',
      to: '2026-10-18T19:00:00.000Z',
      limit: 50,
    })
    expect(result.total).toBe(4)
    expect(result.lines[0]).toEqual({
      time: '2026-10-18T21:23:22.539927500Z',
      level: 'info',
      service: 'bridge',
      message: 'order published to infra',
      requestId: 't23order1',
    })
    expect(result.lines[3].requestId).toBeNull()
  })

  it('omitsFiltersItWasNotGiven', async () => {
    const fetchMock = mockFetch(jsonResponse(200, { lines: [], total: 0 }))
    await queryLogs({})
    expect(fetchMock.mock.calls[0][1]?.body).toBe('{}')
  })

  it('keepsTheBackendErrorWhenTheStoreIsUnavailable', async () => {
    mockFetch(jsonResponse(502, { error: { code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' } }))
    const error = await queryLogs({}).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 502, code: 'LOG_STORE_UNAVAILABLE', message: 'The log store is unavailable' })
  })
})

describe('trailFor', () => {
  it('asksForOneRequestOverADayAndReturnsOldestFirst', async () => {
    const fetchMock = mockFetch(jsonResponse(200, fixture))
    const lines = await trailFor('t23order1')

    const body = JSON.parse(fetchMock.mock.calls[0][1]?.body as string)
    expect(body).toMatchObject({ request_id: 't23order1', limit: 500 })
    expect(Date.parse(body.to) - Date.parse(body.from)).toBe(86_400_000)
    expect(lines.map((l) => l.message)).toEqual([
      '[Producer clientId=producer-1] ProducerId set to 1 with epoch 0',
      'Malformed request body: HttpMessageNotReadableException',
      'rabbitmq publish failed, retrying',
      'order published to infra',
    ])
  })
})
