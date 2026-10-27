package ingest

import (
	"context"
	"fmt"
	"sort"

	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/store"
)

// maxLineQuantity caps one order line's contribution, so a corrupt or absurd quantity (the backend
// does not bound it) cannot pin a SKU at the top of hot:global and every neighbour list.
const maxLineQuantity = 1000

// Apply updates the recommendation state for one completed order, atomically (MULTI/EXEC), so a
// crash never leaves an order half counted. Kafka delivers at least once: a replayed order counts
// twice, which only nudges a weight.
//
// Per distinct SKU of the order (lines repeating a SKU are summed; lines without a SKU or with a
// quantity <= 0 are ignored):
//
//	ZINCRBY user:{userId}:skus  qty sku    (skipped when the order has no userId)
//	ZINCRBY sku:{sku}:buyers    qty userId (inverted index, same condition)
//	ZINCRBY hot:global          qty sku
//
// and for each unordered pair (a, b) of distinct SKUs: ZINCRBY cooccur:{a} 1 b and
// cooccur:{b} 1 a.
func Apply(ctx context.Context, rdb redis.Cmdable, ev OrderCompleted) error {
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

	_, err := rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		for _, s := range skus {
			q := float64(qty[s])
			if ev.UserID != "" {
				p.ZIncrBy(ctx, store.UserSKUs(ev.UserID), q, s)
				p.ZIncrBy(ctx, store.Buyers(s), q, ev.UserID)
			}
			p.ZIncrBy(ctx, store.HotGlobal, q, s)
		}
		for i, a := range skus {
			for _, b := range skus[i+1:] {
				p.ZIncrBy(ctx, store.Cooccur(a), 1, b)
				p.ZIncrBy(ctx, store.Cooccur(b), 1, a)
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("apply order %q: %w", ev.OrderID, err)
	}
	return nil
}
