package ingest

import (
	"context"
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/decay"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/store"
)

// maxLineQuantity caps one order line's contribution, so a corrupt or absurd quantity (the backend
// does not bound it) cannot pin a SKU at the top of hot:global and every neighbour list.
const maxLineQuantity = 1000

// DefaultDedupeTTL is how long the id of an applied order is remembered. It must outlive any
// realistic redelivery (a consumer crash and restart, a rebalance, a lagging group), and Kafka's
// default topic retention is 7 days, so a replay can never be older than that. The cost is one
// ~60 byte key per order, which expires on its own.
const DefaultDedupeTTL = 7 * 24 * time.Hour

// applyOnce runs the model updates unless the order marker KEYS[1] already exists, and then sets
// the marker with the TTL ARGV[1] (seconds). KEYS[2] is the sku:count hash and ARGV[2] = n the
// number of SKUs to count (ARGV[3..n+2], one HINCRBY 1 each). The remaining KEYS[i] (i >= 3) are
// sorted sets, with ARGV[n+2*i-3] the increment and ARGV[n+2*i-2] the member of KEYS[i]. Update and
// marker are one script, so they commit together: a redelivery either sees the marker and the whole
// update, or neither. The marker is written last, so it never exists without the counts; a script
// that fails midway leaves no marker and the retry applies the order again. Returns 1 when
// applied, 0 when the order was a duplicate.
const applyOnce = `
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
local n = tonumber(ARGV[2])
for j = 1, n do
  redis.call('HINCRBY', KEYS[2], ARGV[2 + j], 1)
end
for i = 3, #KEYS do
  redis.call('ZINCRBY', KEYS[i], ARGV[n + 2 * i - 3], ARGV[n + 2 * i - 2])
end
redis.call('SET', KEYS[1], 1, 'EX', ARGV[1])
return 1
`

// Aging weights an event's contribution to hot:global by how long ago it completed, as if the
// periodic decay (decay.Run) had been applied to it since: quantity * Factor^(age/Interval). It is
// what makes a replay of history (the demo seed's preload, a new consumer group) leave hot:global
// where live ingestion plus decay would have. The zero value disables it (weight 1).
type Aging struct {
	// Factor and Interval are the service's HOT_DECAY_FACTOR and HOT_DECAY_INTERVAL.
	Factor   float64
	Interval time.Duration
	// Now is the clock (time.Now when nil).
	Now func() time.Time
}

// Weight returns the multiplier of an event completed at completedAt (an ISO-8601 instant). A
// missing or unparsable instant, a future one (clock skew) or a disabled Aging all weigh 1, so a
// fresh real event is counted in full.
func (a Aging) Weight(completedAt string) float64 {
	if a.Factor <= 0 || a.Factor >= 1 || a.Interval <= 0 || completedAt == "" {
		return 1
	}
	at, err := time.Parse(time.RFC3339Nano, completedAt)
	if err != nil {
		return 1
	}
	now := time.Now
	if a.Now != nil {
		now = a.Now
	}
	age := max(now().Sub(at), 0)
	return math.Pow(a.Factor, float64(age)/float64(a.Interval))
}

// Apply is ApplyAged without aging: every event counts in full toward hot:global.
func Apply(ctx context.Context, rdb redis.Cmdable, ev OrderCompleted, dedupeTTL time.Duration) error {
	return ApplyAged(ctx, rdb, ev, dedupeTTL, Aging{})
}

// ApplyAged updates the recommendation state for one completed order, atomically, so a crash never
// leaves an order half counted. Kafka delivers at least once, so an order carrying an id is applied
// at most once per dedupeTTL (DefaultDedupeTTL in production): the marker processed:{orderId} is
// written in the same atomic step as the counts. An event without an order id cannot be
// recognised and is applied every time (MULTI/EXEC).
//
// Per distinct SKU of the order (lines repeating a SKU are summed; lines without a SKU or with a
// quantity <= 0 are ignored):
//
//	ZINCRBY user:{userId}:skus  qty sku    (skipped when the order has no userId)
//	ZINCRBY sku:{sku}:buyers    qty userId (inverted index, same condition)
//	ZINCRBY hot:global          qty*w sku  (w = aging.Weight(completedAt); skipped when w < decay.Floor)
//	HINCRBY sku:count           1 sku      (orders containing the SKU: the cart score's popularity)
//
// and for each unordered pair (a, b) of distinct SKUs: ZINCRBY cooccur:{a} 1 b and
// cooccur:{b} 1 a.
func ApplyAged(ctx context.Context, rdb redis.Cmdable, ev OrderCompleted, dedupeTTL time.Duration, aging Aging) error {
	qty := map[string]int{}
	for _, it := range ev.Items {
		if it.SKU != "" && it.Quantity > 0 {
			qty[it.SKU] += min(it.Quantity, maxLineQuantity)
		}
	}
	if len(qty) == 0 {
		return nil
	}
	skus := make([]string, 0, len(qty))
	for s := range qty {
		skus = append(skus, s)
	}
	sort.Strings(skus)
	weight := aging.Weight(ev.CompletedAt)

	type incr struct {
		key, member string
		by          float64
	}
	var ops []incr
	for _, s := range skus {
		q := float64(qty[s])
		if ev.UserID != "" {
			ops = append(ops, incr{store.UserSKUs(ev.UserID), s, q}, incr{store.Buyers(s), ev.UserID, q})
		}
		if weight >= decay.Floor {
			ops = append(ops, incr{store.HotGlobal, s, q * weight})
		}
	}
	for i, a := range skus {
		for _, b := range skus[i+1:] {
			ops = append(ops, incr{store.Cooccur(a), b, 1}, incr{store.Cooccur(b), a, 1})
		}
	}

	var err error
	if ev.OrderID != "" && dedupeTTL > 0 {
		keys := []string{store.Processed(ev.OrderID), store.SKUCount}
		args := []any{max(int64(dedupeTTL/time.Second), 1), len(skus)}
		for _, s := range skus {
			args = append(args, s)
		}
		for _, o := range ops {
			keys = append(keys, o.key)
			args = append(args, o.by, o.member)
		}
		err = rdb.Eval(ctx, applyOnce, keys, args...).Err()
	} else {
		_, err = rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
			for _, s := range skus {
				p.HIncrBy(ctx, store.SKUCount, s, 1)
			}
			for _, o := range ops {
				p.ZIncrBy(ctx, o.key, o.by, o.member)
			}
			return nil
		})
	}
	if err != nil {
		return fmt.Errorf("apply order %q: %w", ev.OrderID, err)
	}
	return nil
}
