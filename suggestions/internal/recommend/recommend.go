// Package recommend computes the three kinds of recommendations from the Redis model.
//
// Every read is bounded by the caps in Options, so a request costs a fixed handful of pipelined
// Redis round trips no matter how big the history grows.
package recommend

import (
	"context"
	"math"
	"sort"
	"strconv"

	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/store"
)

// Recommendation is one suggested SKU and its score (higher is better; the scale depends on the
// endpoint and is only comparable within one response).
type Recommendation struct {
	SKU   string  `json:"sku"`
	Score float64 `json:"score"`
}

// Options bounds the work of one request.
type Options struct {
	// CooccurTop is how many co-occurring SKUs are read per cart SKU.
	CooccurTop int64
	// ProfileTop is how many of a user's SKUs (by quantity) define who is "similar".
	ProfileTop int64
	// BuyersTop is how many buyers are read per profile SKU.
	BuyersTop int64
	// Neighbours is how many similar users contribute candidates.
	Neighbours int
	// NeighbourTop is how many SKUs are read per neighbour.
	NeighbourTop int64
}

// DefaultOptions are the production caps. A user request is three Redis round trips: the history, then
// 2*ProfileTop commands (buyers and cardinalities) and Neighbours reads, in two pipelines.
func DefaultOptions() Options {
	return Options{CooccurTop: 100, ProfileTop: 50, BuyersTop: 200, Neighbours: 50, NeighbourTop: 50}
}

// Service reads recommendations from Redis.
type Service struct {
	rdb redis.Cmdable
	opt Options
}

// New builds a Service. Zero fields of opt take their DefaultOptions value.
func New(rdb redis.Cmdable, opt Options) *Service {
	d := DefaultOptions()
	if opt.CooccurTop <= 0 {
		opt.CooccurTop = d.CooccurTop
	}
	if opt.ProfileTop <= 0 {
		opt.ProfileTop = d.ProfileTop
	}
	if opt.BuyersTop <= 0 {
		opt.BuyersTop = d.BuyersTop
	}
	if opt.Neighbours <= 0 {
		opt.Neighbours = d.Neighbours
	}
	if opt.NeighbourTop <= 0 {
		opt.NeighbourTop = d.NeighbourTop
	}
	return &Service{rdb: rdb, opt: opt}
}

// Hot returns the top limit SKUs of hot:global.
func (s *Service) Hot(ctx context.Context, limit int) ([]Recommendation, error) {
	return s.hot(ctx, limit, nil)
}

// hot is Hot without the SKUs in exclude. It over-fetches by len(exclude) so the exclusion never
// shortens the answer.
func (s *Service) hot(ctx context.Context, limit int, exclude map[string]bool) ([]Recommendation, error) {
	zs, err := s.rdb.ZRevRangeWithScores(ctx, store.HotGlobal, 0, int64(limit+len(exclude))-1).Result()
	if err != nil {
		return nil, err
	}
	out := make([]Recommendation, 0, limit)
	for _, z := range zs {
		sku := z.Member.(string)
		if exclude[sku] {
			continue
		}
		out = append(out, Recommendation{SKU: sku, Score: z.Score})
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

// Cart answers "also add": for every SKU b that co-occurs with a cart SKU a it sums the cosine
// score cooccur(a,b) / sqrt(count(a) * count(b)) over the cart, where count is the number of
// orders containing a SKU (sku:count). Dividing by the popularity of both sides keeps a best-seller
// from topping every cart just because it is in every order: raw counts rank it first, the cosine
// ranks the SKU that is bought with the cart SKU out of proportion to how often either sells. The
// cart's own SKUs are skipped and the top limit returned. A SKU without a count (the model was
// built before counts existed) counts as many orders as it co-occurred, which scores the pair 1
// at best instead of failing. When nothing co-occurs (new SKUs, no history) it falls back to the
// hot items not already in the cart. Scores are floats in (0, 1] per cart SKU.
func (s *Service) Cart(ctx context.Context, skus []string, limit int) ([]Recommendation, error) {
	inCart := toSet(skus)
	cmds := make([]*redis.ZSliceCmd, len(skus))
	_, err := s.rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		for i, sku := range skus {
			cmds[i] = p.ZRevRangeWithScores(ctx, store.Cooccur(sku), 0, s.opt.CooccurTop-1)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	// The SKUs whose popularity is needed: the cart's and every candidate (one HMGET).
	wanted := map[string]bool{}
	for i, c := range cmds {
		if len(c.Val()) == 0 {
			continue
		}
		wanted[skus[i]] = true
		for _, z := range c.Val() {
			if sku := z.Member.(string); !inCart[sku] {
				wanted[sku] = true
			}
		}
	}
	if len(wanted) == 0 {
		return s.hot(ctx, limit, inCart)
	}
	fields := make([]string, 0, len(wanted))
	for sku := range wanted {
		fields = append(fields, sku)
	}
	raw, err := s.rdb.HMGet(ctx, store.SKUCount, fields...).Result()
	if err != nil {
		return nil, err
	}
	counts := make(map[string]float64, len(fields))
	for i, v := range raw {
		if str, ok := v.(string); ok {
			if n, perr := strconv.ParseFloat(str, 64); perr == nil && n > 0 {
				counts[fields[i]] = n
			}
		}
	}
	scores := map[string]float64{}
	for i, c := range cmds {
		for _, z := range c.Val() {
			sku := z.Member.(string)
			if inCart[sku] {
				continue
			}
			// count(x) >= cooccur(a,b) always holds in a consistent model; the max also covers the
			// missing count (0) and keeps the score at most 1.
			ca := math.Max(counts[skus[i]], z.Score)
			cb := math.Max(counts[sku], z.Score)
			scores[sku] += z.Score / math.Sqrt(ca*cb)
		}
	}
	if len(scores) == 0 {
		return s.hot(ctx, limit, inCart)
	}
	return top(scores, limit), nil
}

// User answers "what do similar users buy": collaborative filtering over the user->SKU history,
// excluding what the user already bought. A user without history (cold start), or whose SKUs
// nobody else bought, gets the hot items instead.
//
//  1. profile: the user's top ProfileTop SKUs by quantity.
//  2. neighbours: for each profile SKU read its top BuyersTop buyers (sku:{sku}:buyers). A user v
//     earns weight 1/(1+buyers(sku)) per shared SKU (inverse user frequency), so sharing a niche SKU says more than
//     sharing a bestseller. The Neighbours heaviest users are kept.
//  3. candidates: for each neighbour read its top NeighbourTop SKUs; score(sku) is the sum over
//     neighbours of weight(v) * ln(1+quantity(v, sku)). SKUs in the user's whole history are
//     skipped.
func (s *Service) User(ctx context.Context, userID string, limit int) ([]Recommendation, error) {
	// The whole history, not just the profile: it is the exclusion set, and it is bounded by the
	// catalogue size. ZRANGE ... REV keeps it sorted by quantity, so the profile is its head.
	history, err := s.rdb.ZRevRangeWithScores(ctx, store.UserSKUs(userID), 0, -1).Result()
	if err != nil {
		return nil, err
	}
	if len(history) == 0 {
		return s.hot(ctx, limit, nil)
	}
	owned := make(map[string]bool, len(history))
	for _, z := range history {
		owned[z.Member.(string)] = true
	}
	profile := history
	if int64(len(profile)) > s.opt.ProfileTop {
		profile = profile[:s.opt.ProfileTop]
	}
	weights, err := s.neighbours(ctx, userID, profile)
	if err != nil {
		return nil, err
	}
	neigh := topKeys(weights, s.opt.Neighbours)
	cmds := make([]*redis.ZSliceCmd, len(neigh))
	_, err = s.rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		for i, v := range neigh {
			cmds[i] = p.ZRevRangeWithScores(ctx, store.UserSKUs(v), 0, s.opt.NeighbourTop-1)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	scores := map[string]float64{}
	for i, c := range cmds {
		for _, z := range c.Val() {
			if sku := z.Member.(string); !owned[sku] {
				scores[sku] += weights[neigh[i]] * math.Log1p(z.Score)
			}
		}
	}
	if len(scores) == 0 {
		return s.hot(ctx, limit, owned)
	}
	return top(scores, limit), nil
}

// neighbours returns the similarity weight of every other user sharing a profile SKU.
func (s *Service) neighbours(ctx context.Context, userID string, profile []redis.Z) (map[string]float64, error) {
	buyers := make([]*redis.ZSliceCmd, len(profile))
	sizes := make([]*redis.IntCmd, len(profile))
	_, err := s.rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		for i, z := range profile {
			key := store.Buyers(z.Member.(string))
			buyers[i] = p.ZRevRangeWithScores(ctx, key, 0, s.opt.BuyersTop-1)
			sizes[i] = p.ZCard(ctx, key)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	weights := map[string]float64{}
	for i := range profile {
		w := 1 / (1 + float64(sizes[i].Val()))
		for _, b := range buyers[i].Val() {
			if v := b.Member.(string); v != userID {
				weights[v] += w
			}
		}
	}
	return weights, nil
}

func toSet(skus []string) map[string]bool {
	set := make(map[string]bool, len(skus))
	for _, s := range skus {
		set[s] = true
	}
	return set
}

// top sorts by score descending (ties by SKU, so answers are deterministic) and keeps limit.
func top(scores map[string]float64, limit int) []Recommendation {
	out := make([]Recommendation, 0, len(scores))
	for sku, sc := range scores {
		out = append(out, Recommendation{SKU: sku, Score: sc})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Score != out[j].Score {
			return out[i].Score > out[j].Score
		}
		return out[i].SKU < out[j].SKU
	})
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

func topKeys(weights map[string]float64, n int) []string {
	recs := top(weights, n)
	keys := make([]string, len(recs))
	for i, r := range recs {
		keys[i] = r.SKU
	}
	return keys
}
