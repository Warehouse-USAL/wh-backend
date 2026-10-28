// Package decay ages hot:global so recent demand outranks old demand.
package decay

import (
	"context"
	"log/slog"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/store"
)

// Floor is the score below which a SKU is dropped from hot:global: after enough decay rounds an
// idle SKU is noise, and removing it keeps the set from growing without bound. Ingestion uses it
// too, to not add what the next round would remove.
const Floor = 0.01

// script multiplies every score of KEYS[1] by ARGV[1] in one atomic step and removes entries that
// fall under ARGV[2]. It returns the number of members it visited.
const script = `
local items = redis.call('ZRANGE', KEYS[1], 0, -1, 'WITHSCORES')
local factor = tonumber(ARGV[1])
local floor = tonumber(ARGV[2])
local n = 0
for i = 1, #items, 2 do
  local s = tonumber(items[i + 1]) * factor
  if s < floor then
    redis.call('ZREM', KEYS[1], items[i])
  else
    redis.call('ZADD', KEYS[1], s, items[i])
  end
  n = n + 1
end
return n
`

// Once multiplies every hot:global score by factor (EVAL) and returns how many SKUs it visited.
func Once(ctx context.Context, rdb redis.Cmdable, factor float64) (int64, error) {
	return rdb.Eval(ctx, script, []string{store.HotGlobal}, factor, Floor).Int64()
}

// Run calls Once every interval until ctx is done. A failed round is logged and retried on the
// next tick: a missed round only leaves the scores a little less decayed.
func Run(ctx context.Context, rdb redis.Cmdable, factor float64, interval time.Duration, log *slog.Logger) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			n, err := Once(ctx, rdb, factor)
			if err != nil {
				log.Error("hot:global decay failed", "error", err)
				continue
			}
			log.Debug("hot:global decayed", "skus", n, "factor", factor)
		}
	}
}
