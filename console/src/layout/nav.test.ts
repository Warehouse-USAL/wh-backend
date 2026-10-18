import { describe, expect, it } from 'vitest'
import { NAV } from './nav'

describe('NAV', () => {
  it('navHasTheFiveSectionsInOrder', () => {
    expect(NAV.map((s) => s.section)).toEqual(['Overview', 'Monitoring', 'Infrastructure', 'Domains', 'Dev tools'])
    expect(NAV[2].items.map((i) => i.label)).toEqual([
      'Backend', 'MongoDB', 'Bridge', 'Kafka', 'MinIO', 'Restock cron', 'Edge (prod)', 'Telemetry', 'Logs',
    ])
  })

  it('everyNavPathIsUnique', () => {
    const paths = NAV.flatMap((s) => s.items.map((i) => i.path))
    expect(new Set(paths).size).toBe(paths.length)
    expect(paths.every((p) => /^\/[a-z-]+(\/[a-z-]+)?$/.test(p))).toBe(true)
  })
})
