package recommend

// Backtest (RFC section 8). Synthetic order.completed events with planted behaviour are fed
// through the real ingest path into miniredis; the recommender is then scored with precision@k
// against held-out purchases and compared with the "always return the hot items" baseline.
//
// Nothing here touches wh-backend's DemoDataset: the events are generated in the test with a
// fixed seed, so the run is deterministic.

import (
	"fmt"
	"math/rand/v2"
	"testing"

	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/decay"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
)

const k = 3

// precisionAtK is |top-k ∩ truth| / k.
func precisionAtK(recs []Recommendation, truth map[string]bool) float64 {
	hits := 0
	for i, r := range recs {
		if i == k {
			break
		}
		if truth[r.SKU] {
			hits++
		}
	}
	return float64(hits) / k
}

func mean(xs []float64) float64 {
	sum := 0.0
	for _, x := range xs {
		sum += x
	}
	return sum / float64(len(xs))
}

// background fills the store with noise: many users ordering mostly from a small set of popular
// SKUs, so hot:global is dominated by SKUs unrelated to every planted pattern.
func background(t *testing.T, rdb redis.Cmdable, rng *rand.Rand, users int) {
	t.Helper()
	popular := []string{"POP-1", "POP-2", "POP-3", "POP-4", "POP-5"}
	for u := range users {
		for range 3 {
			ev := ingest.OrderCompleted{UserID: fmt.Sprintf("bg-%d", u)}
			for _, i := range rng.Perm(len(popular))[:1+rng.IntN(3)] {
				ev.Items = append(ev.Items, ingest.Item{SKU: popular[i], Quantity: 1 + rng.IntN(3)})
			}
			if err := ingest.Apply(ctx, rdb, ev, ingest.DefaultDedupeTTL); err != nil {
				t.Fatal(err)
			}
		}
	}
}

func apply(t *testing.T, rdb redis.Cmdable, user string, skus ...string) {
	t.Helper()
	order(t, rdb, user, skus...)
}

func TestBacktestCartPairs(t *testing.T) {
	_, rdb, svc := setup(t)
	rng := rand.New(rand.NewPCG(1, 2))
	background(t, rdb, rng, 60)

	// Planted: five pairs that one dedicated user always orders together, plus some solo orders of
	// the first half so the pair is not the only thing that half ever appears in.
	type pair struct{ a, b string }
	pairs := []pair{{"TEC-001", "TEC-002"}, {"TEC-003", "TEC-004"}, {"CAB-001", "CAB-002"}, {"BAT-001", "BAT-002"}, {"SEN-001", "SEN-002"}}
	for i, p := range pairs {
		user := fmt.Sprintf("pair-user-%d", i)
		for range 8 {
			apply(t, rdb, user, p.a, p.b)
		}
		for range 2 {
			apply(t, rdb, user, p.a)
		}
		// Noise orders that also include the first half with a popular SKU.
		apply(t, rdb, "bg-1", p.a, "POP-1")
	}

	var system, baseline []float64
	for _, p := range pairs {
		truth := map[string]bool{p.b: true}
		got, err := svc.Cart(ctx, []string{p.a}, k)
		if err != nil {
			t.Fatal(err)
		}
		if !contains(got, p.b) {
			t.Errorf("cart [%s]: planted partner %s not in top-%d: %+v", p.a, p.b, k, got)
		}
		system = append(system, precisionAtK(got, truth))
		base, _ := svc.hot(ctx, k, map[string]bool{p.a: true})
		baseline = append(baseline, precisionAtK(base, truth))
	}
	t.Logf("cart precision@%d: system=%.3f baseline(hot)=%.3f", k, mean(system), mean(baseline))
	if mean(system) <= mean(baseline) || mean(system) < 0.3 {
		t.Fatalf("cart recommender does not beat the hot baseline: %.3f vs %.3f", mean(system), mean(baseline))
	}
}

// A niche partner that is always bought with the anchor loses to the best-sellers on raw counts: the
// anchor is ordered 12 times with each of three popular SKUs (and 6 times with its partner), so the
// summed co-occurrence puts the popular trio first. Normalised by popularity the partner leads.
func TestBacktestCartPopularityBias(t *testing.T) {
	_, rdb, svc := setup(t)
	rng := rand.New(rand.NewPCG(7, 8))
	background(t, rdb, rng, 60)

	type pair struct{ a, b string }
	pairs := []pair{{"ANC-1", "NICHE-1"}, {"ANC-2", "NICHE-2"}, {"ANC-3", "NICHE-3"}, {"ANC-4", "NICHE-4"}}
	for i, p := range pairs {
		user := fmt.Sprintf("bias-user-%d", i)
		for range 6 {
			apply(t, rdb, user, p.a, p.b)
		}
		for _, pop := range []string{"POP-1", "POP-2", "POP-3"} {
			for range 12 {
				apply(t, rdb, fmt.Sprintf("bias-pop-%d", i), p.a, pop)
			}
		}
	}

	var system, baseline []float64
	for _, p := range pairs {
		truth := map[string]bool{p.b: true}
		got, err := svc.Cart(ctx, []string{p.a}, k)
		if err != nil {
			t.Fatal(err)
		}
		system = append(system, precisionAtK(got, truth))
		base, _ := svc.hot(ctx, k, map[string]bool{p.a: true})
		baseline = append(baseline, precisionAtK(base, truth))
	}
	t.Logf("cart popularity-bias precision@%d: system=%.3f baseline(hot)=%.3f", k, mean(system), mean(baseline))
	if mean(system) <= mean(baseline) || mean(system) < 0.3 {
		t.Fatalf("niche partners buried under best-sellers: %.3f vs %.3f", mean(system), mean(baseline))
	}
}

func TestBacktestUserSimilarUsers(t *testing.T) {
	_, rdb, svc := setup(t)
	rng := rand.New(rand.NewPCG(3, 4))
	background(t, rdb, rng, 60)

	// Planted: a group of ten users with a shared taste of four niche SKUs. Each user bought three
	// of the four (user i never bought group[i%4]); the held-out purchase is the one they missed.
	group := []string{"NICHE-A", "NICHE-B", "NICHE-C", "NICHE-D"}
	var users []string
	for i := range 10 {
		u := fmt.Sprintf("group-%d", i)
		users = append(users, u)
		var bought []string
		for j, s := range group {
			if j != i%len(group) {
				bought = append(bought, s)
			}
		}
		apply(t, rdb, u, bought...)
		apply(t, rdb, u, "POP-1") // they also buy a bestseller, like everybody else
	}

	var system, baseline []float64
	for i, u := range users {
		truth := map[string]bool{group[i%len(group)]: true}
		got, err := svc.User(ctx, u, k)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range got {
			if r.SKU == "POP-1" {
				t.Errorf("user %s: already bought %s recommended", u, r.SKU)
			}
		}
		system = append(system, precisionAtK(got, truth))
		owned := map[string]bool{"POP-1": true}
		for j, s := range group {
			if j != i%len(group) {
				owned[s] = true
			}
		}
		base, _ := svc.hot(ctx, k, owned)
		baseline = append(baseline, precisionAtK(base, truth))
	}
	t.Logf("user precision@%d: system=%.3f baseline(hot)=%.3f", k, mean(system), mean(baseline))
	if mean(system) <= mean(baseline) || mean(system) < 0.3 {
		t.Fatalf("user recommender does not beat the hot baseline: %.3f vs %.3f", mean(system), mean(baseline))
	}
}

func TestBacktestTrendingSKU(t *testing.T) {
	_, rdb, svc := setup(t)
	rng := rand.New(rand.NewPCG(5, 6))
	background(t, rdb, rng, 20)

	// Last year's star sells a lot, then time passes (a decay round every 15 minutes for ~two
	// weeks), then a new SKU takes off.
	for range 400 {
		apply(t, rdb, "old-fan", "OLD-STAR")
	}
	before, _ := svc.Hot(ctx, 1)
	if before[0].SKU != "OLD-STAR" {
		t.Fatalf("setup: expected OLD-STAR on top before decay, got %+v", before)
	}
	for range 1300 {
		if _, err := decay.Once(ctx, rdb, 0.97); err != nil {
			t.Fatal(err)
		}
	}
	for i := range 60 {
		apply(t, rdb, fmt.Sprintf("trend-%d", i), "TREND-NEW", "POP-2")
	}
	got, err := svc.Hot(ctx, k)
	if err != nil {
		t.Fatal(err)
	}
	if got[0].SKU != "TREND-NEW" || contains(got, "OLD-STAR") {
		t.Fatalf("trending SKU should lead and the stale star be gone, got %+v", got)
	}
}

func contains(recs []Recommendation, sku string) bool {
	for _, r := range recs {
		if r.SKU == sku {
			return true
		}
	}
	return false
}
