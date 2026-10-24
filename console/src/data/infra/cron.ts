const MINUTE = 60_000
const DAY = 86_400_000

/**
 * restock-cron's schedule unless `RESTOCK_SCHEDULE` overrides it (`docker-compose.yml`). The console
 * cannot read the container's environment, so a deployment that overrides it shows this default.
 */
export const RESTOCK_SCHEDULE = '0 6 * * *'

/**
 * The cron container's zone, as minutes ahead of UTC: busybox crond in the curl image has no
 * tzdata, so the schedule runs in UTC (`config/restock-cron/restock-cron.sh` logs it as such).
 */
export const RESTOCK_SCHEDULE_OFFSET_MIN = 0

/**
 * The next time strictly after `now` (epoch ms) that a daily `m h * * *` schedule fires, read on a
 * wall clock `offsetMin` minutes ahead of UTC. A fixed offset has no DST: pass the offset the cron
 * container runs at (0, UTC). Firing at exactly `now` counts as this run, so the answer is the next
 * day's. Any other cron form gives undefined.
 */
export function nextDailyRun(schedule: string, now: number, offsetMin = 0): number | undefined {
  const match = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(schedule.trim().replace(/\s+/g, ' '))
  if (!match) return undefined
  const [minute, hour] = [Number(match[1]), Number(match[2])]
  if (minute > 59 || hour > 23) return undefined
  const offset = offsetMin * MINUTE
  const wall = now + offset
  let run = Math.floor(wall / DAY) * DAY + (hour * 60 + minute) * MINUTE
  if (run <= wall) run += DAY
  return run - offset
}
