import type { LogLine, LogsQuery } from '@/api/logs'
import type { MetricQuery } from '@/api/metrics'
import type { Range } from '@/data/live'
import type { Series, Tone } from '@/widgets/scale'

/*
 * Declarative page specs: `PresetPage` renders any of them, so a page is data (queries plus the
 * pure steps that turn answers into what a widget shows), never a hand-written component.
 */

/** Columns of the 12-column group grid. */
export type Span = 2 | 3 | 4 | 6 | 8 | 12

/** What a widget's pure steps may depend on besides the answered series. */
export interface Ctx {
  range: Range
  /** Epoch ms, refreshed by the page's UI clock. */
  now: number
}

/** A fixed text, or one derived from the answer (e.g. "of 200" from the max series). */
export type Text = string | ((ctx: Ctx, series: Series[]) => string)

export interface TileValue {
  value: string
  tone: Tone
  /** Replaces the spec's title as the caption (e.g. "Last run, 06:00"). */
  label?: string
  spark?: number[]
}

export interface StreamRow {
  time: string
  tag: string
  tone?: Tone | 'info'
  text: string
}

/** One service row of `GET /console/status`: its state, or (backend only) its uptime. */
export interface StatusQuery {
  service: string
  show: 'state' | 'uptime'
}

interface Base {
  title: string
  sub?: Text
  span: Span
}

export interface ChartSpec extends Base {
  kind: 'bars' | 'lines'
  query: MetricQuery[]
  /** From the answered series to the plotted ones (order, labels, units); as answered when absent. */
  select?: (series: Series[], ctx: Ctx) => Series[]
  /** Lines only: value format (counts by default). */
  format?: (v: number) => string
  area?: boolean
}

export interface TileSpec extends Base {
  kind: 'tile'
  /** Empty for a tile computed from `ctx` alone (the cron's next run). */
  query: MetricQuery[]
  /** Undefined when there is nothing to show: the tile shows the empty state, never a zero. */
  value: (series: Series[], ctx: Ctx) => TileValue | undefined
}

export interface ToplistSpec extends Base {
  kind: 'toplist'
  query: MetricQuery[]
  rows: (series: Series[], ctx: Ctx) => { name: string; value: number }[]
  color: string
  format: (v: number) => string
}

export interface LogStreamSpec extends Base {
  kind: 'stream'
  source: 'logs'
  query: Omit<LogsQuery, 'from' | 'to'>
  rows: (lines: LogLine[]) => StreamRow[]
}

/** Rows derived from metric samples (freshness per metric, runs of a job). */
export interface MetricStreamSpec extends Base {
  kind: 'stream'
  source: 'metrics'
  query: MetricQuery[]
  rows: (series: Series[], ctx: Ctx) => StreamRow[]
}

export interface StatusSpec extends Base {
  kind: 'status'
  query: StatusQuery
}

export type WidgetSpec = ChartSpec | TileSpec | ToplistSpec | LogStreamSpec | MetricStreamSpec | StatusSpec

export interface GroupSpec {
  title: string
  /** Names (a collection, a container) are set in mono, as on the boards. */
  mono?: boolean
  tag?: string
  /** A small chip on the right (the auth a callback needs). */
  right?: string
  /** The header tab that scrolls to this group. */
  tab?: string
  widgets: WidgetSpec[]
}

export interface PageSpec {
  crumb: string
  title: string
  tabs: string[]
  chips: string[]
  groups: GroupSpec[]
  /** What an empty widget says instead of "No data in this range" (the Edge page outside prod). */
  empty?: string
  /** Shown under the groups: what the page cannot show and why. */
  note?: string
}

/** Every metric query of a page, in order (tiles, charts, lists and streams alike). */
export function metricQueries(page: PageSpec): MetricQuery[] {
  return page.groups.flatMap((g) => g.widgets.flatMap((w) => (Array.isArray(w.query) ? w.query : [])))
}
