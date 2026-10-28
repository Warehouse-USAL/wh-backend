package recommend

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
)

var ctx = context.Background()

func setup(t *testing.T) (*miniredis.Miniredis, *redis.Client, *Service) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr(), MaxRetries: -1})
	t.Cleanup(func() { _ = rdb.Close() })
	return mr, rdb, New(rdb, Options{})
}

var orderSeq atomic.Int64

// order applies one completed order for user with sku:quantity pairs (quantity 1 by default).
func order(t *testing.T, rdb redis.Cmdable, user string, skus ...string) {
	t.Helper()
	// Each call is a distinct order: a repeated id would be dropped as a redelivery.
	ev := ingest.OrderCompleted{OrderID: fmt.Sprintf("o-%d", orderSeq.Add(1)), UserID: user}
	for _, s := range skus {
		ev.Items = append(ev.Items, ingest.Item{SKU: s, Quantity: 1})
	}
	if err := ingest.Apply(ctx, rdb, ev, ingest.DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
}

func skusOf(recs []Recommendation) []string {
	out := make([]string, len(recs))
	for i, r := range recs {
		out[i] = r.SKU
	}
	return out
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestNewAppliesDefaults(t *testing.T) {
	_, rdb, _ := setup(t)
	if s := New(rdb, Options{}); s.opt != DefaultOptions() {
		t.Fatalf("opt = %+v", s.opt)
	}
	if s := New(rdb, Options{Neighbours: 3}); s.opt.Neighbours != 3 {
		t.Fatalf("explicit option lost: %+v", s.opt)
	}
}

func TestHotOrdersAndLimits(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "u1", "A", "B")
	order(t, rdb, "u2", "B")
	order(t, rdb, "u3", "B", "C")
	order(t, rdb, "u4", "A")
	recs, err := s.Hot(ctx, 2)
	if err != nil || !equal(skusOf(recs), []string{"B", "A"}) || recs[0].Score != 3 {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestCartSumsCooccurrenceAndExcludesCart(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "u1", "A", "B")
	order(t, rdb, "u2", "A", "B")
	order(t, rdb, "u3", "A", "C")
	order(t, rdb, "u4", "B", "C")
	recs, err := s.Cart(ctx, []string{"A", "B"}, 5)
	if err != nil {
		t.Fatal(err)
	}
	// C co-occurs once with A and once with B; A and B are in the cart.
	if len(recs) != 1 || recs[0].SKU != "C" || recs[0].Score != 2 {
		t.Fatalf("recs = %+v", recs)
	}
	recs, _ = s.Cart(ctx, []string{"A"}, 1)
	if len(recs) != 1 || recs[0].SKU != "B" {
		t.Fatalf("limit/order wrong: %+v", recs)
	}
}

func TestCartColdStartFallsBackToHotWithoutCartSKUs(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "u1", "A", "B")
	order(t, rdb, "u2", "A")
	recs, err := s.Cart(ctx, []string{"NEW", "A"}, 1)
	if err != nil {
		t.Fatal(err)
	}
	// NEW co-occurs with nothing; A is in the cart, so the hot fallback answers B.
	if len(recs) != 1 || recs[0].SKU != "B" {
		t.Fatalf("recs = %+v", recs)
	}
}

func TestUserRecommendsWhatSimilarUsersBought(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "me", "A", "B")
	order(t, rdb, "twin1", "A", "B", "X")
	order(t, rdb, "twin2", "A", "B", "X", "Y")
	order(t, rdb, "stranger", "Q", "Z", "Z")
	for range 10 {
		order(t, rdb, "stranger", "Z") // Z is the global bestseller but nobody similar bought it
	}
	recs, err := s.User(ctx, "me", 5)
	if err != nil {
		t.Fatal(err)
	}
	if !equal(skusOf(recs), []string{"X", "Y"}) {
		t.Fatalf("recs = %+v", recs)
	}
	if recs[0].Score <= recs[1].Score {
		t.Errorf("X (two neighbours) should outscore Y (one): %+v", recs)
	}
}

func TestUserIgnoresSKUsAlreadyBoughtEvenOutsideProfile(t *testing.T) {
	_, rdb, _ := setup(t)
	s := New(rdb, Options{ProfileTop: 1})
	order(t, rdb, "me", "A")
	order(t, rdb, "me", "A") // A is the profile (top 1)
	order(t, rdb, "me", "B") // B is owned but outside the profile
	order(t, rdb, "other", "A", "B", "C")
	recs, err := s.User(ctx, "me", 5)
	if err != nil || !equal(skusOf(recs), []string{"C"}) {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestUserColdStartIsHot(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "u1", "A")
	order(t, rdb, "u2", "A", "B")
	recs, err := s.User(ctx, "brand-new", 2)
	if err != nil || !equal(skusOf(recs), []string{"A", "B"}) {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestUserWithoutNeighboursGetsHotMinusOwned(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "me", "A")
	order(t, rdb, "u2", "B", "C")
	order(t, rdb, "u3", "B")
	recs, err := s.User(ctx, "me", 2)
	if err != nil || !equal(skusOf(recs), []string{"B", "C"}) {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestUserWhoseNeighboursBoughtNothingNewGetsHot(t *testing.T) {
	_, rdb, s := setup(t)
	order(t, rdb, "me", "A", "B")
	order(t, rdb, "twin", "A")
	order(t, rdb, "u3", "C")
	recs, err := s.User(ctx, "me", 5)
	if err != nil || !equal(skusOf(recs), []string{"C"}) {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestNeighbourCapsBoundTheWork(t *testing.T) {
	_, rdb, _ := setup(t)
	s := New(rdb, Options{BuyersTop: 2, Neighbours: 1})
	order(t, rdb, "me", "A")
	for i := range 6 {
		order(t, rdb, fmt.Sprintf("v%d", i), "A", fmt.Sprintf("P%d", i))
	}
	// Only one neighbour is consulted, so at most one of the six P SKUs can come back.
	recs, err := s.User(ctx, "me", 10)
	if err != nil || len(recs) != 1 {
		t.Fatalf("recs = %+v err = %v", recs, err)
	}
}

func TestRedisErrorsSurface(t *testing.T) {
	mr, rdb, s := setup(t)
	order(t, rdb, "me", "A")
	mr.Close()
	if _, err := s.Hot(ctx, 1); err == nil {
		t.Error("Hot: want error")
	}
	if _, err := s.Cart(ctx, []string{"A"}, 1); err == nil {
		t.Error("Cart: want error")
	}
	if _, err := s.User(ctx, "me", 1); err == nil {
		t.Error("User: want error")
	}
}

// Redis dying before the nth pipeline of a User call exercises each later error path.
func TestUserErrorsInEachPipeline(t *testing.T) {
	for n := 1; n <= 2; n++ {
		mr, rdb, s := setup(t)
		order(t, rdb, "me", "A")
		order(t, rdb, "o", "A", "B")
		rdb.AddHook(&killOnPipeline{n: n, mr: mr})
		if _, err := s.User(ctx, "me", 1); err == nil {
			t.Errorf("pipeline %d: want error", n)
		}
	}
}

type killOnPipeline struct {
	n    int
	seen int
	mr   *miniredis.Miniredis
}

func (*killOnPipeline) DialHook(next redis.DialHook) redis.DialHook { return next }
func (*killOnPipeline) ProcessHook(next redis.ProcessHook) redis.ProcessHook {
	return next
}
func (k *killOnPipeline) ProcessPipelineHook(next redis.ProcessPipelineHook) redis.ProcessPipelineHook {
	return func(ctx context.Context, cmds []redis.Cmder) error {
		if k.seen++; k.seen == k.n {
			k.mr.Close()
		}
		return next(ctx, cmds)
	}
}
