package ingest

import (
	"context"
	"errors"
	"math"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

// Exactly what wh-backend's OrderCompletedMessage serialises to (Instant.toString has fractions).
const backendPayload = `{
  "orderId": "o-0421",
  "userId": "u-operator-3",
  "destinationArea": "Sucursal Pocitos",
  "items": [
    { "sku": "TEC-002", "productId": "p-TEC-002", "quantity": 2 },
    { "sku": "TEC-004", "productId": "p-TEC-004", "quantity": 1 }
  ],
  "completedAt": "2026-08-30T14:22:05.123456Z"
}`

func newRedis(t *testing.T) (*miniredis.Miniredis, *redis.Client) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return mr, rdb
}

func TestDecodeBackendContract(t *testing.T) {
	ev, err := Decode([]byte(backendPayload))
	if err != nil {
		t.Fatal(err)
	}
	if ev.OrderID != "o-0421" || ev.UserID != "u-operator-3" || ev.DestinationArea != "Sucursal Pocitos" ||
		len(ev.Items) != 2 || ev.Items[0].Quantity != 2 || ev.Items[1].ProductID != "p-TEC-004" ||
		ev.CompletedAt != "2026-08-30T14:22:05.123456Z" {
		t.Fatalf("decoded wrongly: %+v", ev)
	}
}

// The backend adds fields to the payload without a version bump; old events lack the new one and
// future ones may carry more. Both must decode, applying exactly as before.
func TestDecodeToleratesNewAndMissingFields(t *testing.T) {
	ev, err := Decode([]byte(`{"orderId":"o","items":[
		{"sku":"A","productId":"p-A","quantity":1,"fromRecommendation":true},
		{"sku":"B","productId":"p-B","quantity":2},
		{"sku":"C","productId":"p-C","quantity":3,"fromRecommendation":false,"someFutureField":{"x":1}}],
		"someFutureField":[1,2]}`))
	if err != nil {
		t.Fatal(err)
	}
	got := []bool{ev.Items[0].FromRecommendation, ev.Items[1].FromRecommendation, ev.Items[2].FromRecommendation}
	if !got[0] || got[1] || got[2] {
		t.Fatalf("fromRecommendation = %v, want [true false false]", got)
	}
}

func TestDecodeNullCompletedAtAndBadJSON(t *testing.T) {
	if _, err := Decode([]byte(`{"orderId":"o","items":[],"completedAt":null}`)); err != nil {
		t.Fatal(err)
	}
	// A malformed instant must not drop the order: nothing reads it.
	if _, err := Decode([]byte(`{"orderId":"o","items":[],"completedAt":"garbage"}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := Decode([]byte(`not json`)); !errors.Is(err, ErrInvalidEvent) {
		t.Fatalf("err = %v", err)
	}
}

func TestApplyWritesTheModel(t *testing.T) {
	mr, rdb := newRedis(t)
	ev, _ := Decode([]byte(backendPayload))
	if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	want := map[string]map[string]float64{
		"user:u-operator-3:skus": {"TEC-002": 2, "TEC-004": 1},
		"hot:global":             {"TEC-002": 2, "TEC-004": 1},
		"cooccur:TEC-002":        {"TEC-004": 1},
		"cooccur:TEC-004":        {"TEC-002": 1},
		"sku:TEC-002:buyers":     {"u-operator-3": 2},
		"sku:TEC-004:buyers":     {"u-operator-3": 1},
	}
	for key, members := range want {
		for m, score := range members {
			got, err := mr.ZScore(key, m)
			if err != nil || got != score {
				t.Errorf("%s[%s] = %v, %v; want %v", key, m, got, err, score)
			}
		}
	}
}

// scores snapshots every sorted set so tests can compare whole model states.
func scores(t *testing.T, mr *miniredis.Miniredis) map[string]map[string]float64 {
	t.Helper()
	out := map[string]map[string]float64{}
	for _, k := range mr.Keys() {
		if typ := mr.Type(k); typ != "zset" {
			continue
		}
		members, _ := mr.ZMembers(k)
		out[k] = map[string]float64{}
		for _, m := range members {
			out[k][m], _ = mr.ZScore(k, m)
		}
	}
	return out
}

func TestApplyIgnoresRedeliveredOrder(t *testing.T) {
	mr, rdb := newRedis(t)
	ev, _ := Decode([]byte(backendPayload))
	ctx := context.Background()
	if err := Apply(ctx, rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	first := scores(t, mr)
	for range 3 {
		if err := Apply(ctx, rdb, ev, DefaultDedupeTTL); err != nil {
			t.Fatal(err)
		}
	}
	if got := scores(t, mr); !reflect.DeepEqual(got, first) {
		t.Fatalf("replay changed the model:\n got %v\nwant %v", got, first)
	}
	if got, _ := mr.ZScore("cooccur:TEC-002", "TEC-004"); got != 1 {
		t.Errorf("cooccur = %v, want 1", got)
	}
	if got, _ := mr.ZScore("hot:global", "TEC-002"); got != 2 {
		t.Errorf("hot = %v, want 2", got)
	}
	if ttl := mr.TTL("processed:o-0421"); ttl != DefaultDedupeTTL {
		t.Errorf("marker ttl = %v, want %v", ttl, DefaultDedupeTTL)
	}
}

func TestApplyCountsDistinctOrders(t *testing.T) {
	mr, rdb := newRedis(t)
	ctx := context.Background()
	items := []Item{{SKU: "A", Quantity: 1}, {SKU: "B", Quantity: 1}}
	for _, id := range []string{"o1", "o2"} {
		if err := Apply(ctx, rdb, OrderCompleted{OrderID: id, UserID: "u", Items: items}, DefaultDedupeTTL); err != nil {
			t.Fatal(err)
		}
	}
	if got, _ := mr.ZScore("cooccur:A", "B"); got != 2 {
		t.Errorf("cooccur = %v, want 2", got)
	}
	if got, _ := mr.ZScore("user:u:skus", "A"); got != 2 {
		t.Errorf("user qty = %v, want 2", got)
	}
}

func TestApplyAgainAfterMarkerExpires(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{OrderID: "o1", Items: []Item{{SKU: "A", Quantity: 1}}}
	ttl := time.Minute
	for range 2 {
		if err := Apply(context.Background(), rdb, ev, ttl); err != nil {
			t.Fatal(err)
		}
		mr.FastForward(2 * ttl)
	}
	if got, _ := mr.ZScore("hot:global", "A"); got != 2 {
		t.Errorf("hot = %v, want 2 (window elapsed)", got)
	}
}

func TestApplyMarkerAndCountsCommitTogether(t *testing.T) {
	mr, rdb := newRedis(t)
	ctx := context.Background()
	// A failing update (wrong type under a key the order touches) must not leave a marker, so the
	// retry is not mistaken for a duplicate.
	if err := mr.Set("hot:global", "not a zset"); err != nil {
		t.Fatal(err)
	}
	ev := OrderCompleted{OrderID: "o1", Items: []Item{{SKU: "A", Quantity: 1}}}
	if err := Apply(ctx, rdb, ev, DefaultDedupeTTL); err == nil {
		t.Fatal("want error")
	}
	if mr.Exists("processed:o1") {
		t.Fatal("marker written although the update failed")
	}
	mr.Del("hot:global")
	if err := Apply(ctx, rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if !mr.Exists("processed:o1") {
		t.Fatal("marker missing after a successful apply")
	}
	if got, _ := mr.ZScore("hot:global", "A"); got != 1 {
		t.Errorf("hot = %v, want 1", got)
	}
}

func TestApplyWithoutOrderIDKeepsCountingEveryDelivery(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{UserID: "u", Items: []Item{{SKU: "A", Quantity: 1}, {SKU: "B", Quantity: 1}}}
	for range 2 {
		if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
			t.Fatal(err)
		}
	}
	if got, _ := mr.ZScore("cooccur:A", "B"); got != 2 {
		t.Errorf("cooccur = %v, want 2", got)
	}
	for _, k := range mr.Keys() {
		if strings.HasPrefix(k, "processed:") {
			t.Errorf("unexpected marker %s", k)
		}
	}
}

func TestApplyOrderIDCannotReachOtherKeys(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{OrderID: "x\r\nhot:global", Items: []Item{{SKU: "A", Quantity: 1}}}
	if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if !mr.Exists("processed:x\r\nhot:global") || mr.Type("hot:global") != "zset" {
		t.Errorf("keys = %q", mr.Keys())
	}
}

func TestApplyMergesRepeatedSKUsAndSkipsJunk(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{UserID: "u", Items: []Item{
		{SKU: "A", Quantity: 1}, {SKU: "A", Quantity: 2}, {SKU: "", Quantity: 5}, {SKU: "B", Quantity: 0}, {SKU: "C", Quantity: -1},
	}}
	if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("user:u:skus", "A"); got != 3 {
		t.Errorf("A = %v, want 3", got)
	}
	// A single distinct SKU has no pair.
	if mr.Exists("cooccur:A") || mr.Exists("user:u:skus") == false || len(mr.Keys()) != 4 { // user, buyers, hot and sku:count
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyWithoutUserKeepsHotAndPairs(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{Items: []Item{{SKU: "A", Quantity: 1}, {SKU: "B", Quantity: 1}}}
	if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if !mr.Exists("hot:global") || !mr.Exists("cooccur:A") || mr.Exists("sku:A:buyers") {
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyEmptyOrderIsNoop(t *testing.T) {
	mr, rdb := newRedis(t)
	if err := Apply(context.Background(), rdb, OrderCompleted{OrderID: "o"}, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if len(mr.Keys()) != 0 {
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyRedisDown(t *testing.T) {
	mr, rdb := newRedis(t)
	mr.Close()
	err := Apply(context.Background(), rdb, OrderCompleted{OrderID: "o9", Items: []Item{{SKU: "A", Quantity: 1}}}, DefaultDedupeTTL)
	if err == nil {
		t.Fatal("want error")
	}
}

func TestApplyCapsAbsurdQuantity(t *testing.T) {
	_, rdb := newRedis(t)
	ev := OrderCompleted{UserID: "u", Items: []Item{{SKU: "A", Quantity: 2147483647}}}
	if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
		t.Fatal(err)
	}
	if got := rdb.ZScore(context.Background(), "hot:global", "A").Val(); got != maxLineQuantity {
		t.Fatalf("hot score = %v, want %d", got, maxLineQuantity)
	}
}

var agingNow = time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)

// Half-life of one hour, so every expected weight is a power of 1/2 worked out by hand.
func halfHourly() Aging {
	return Aging{Factor: 0.5, Interval: time.Hour, Now: func() time.Time { return agingNow }}
}

func TestAgingWeight(t *testing.T) {
	a := halfHourly()
	for name, tc := range map[string]struct {
		completedAt string
		want        float64
	}{
		"now":                  {"2026-10-28T12:00:00Z", 1},
		"one interval":         {"2026-10-28T11:00:00Z", 0.5},
		"two intervals":        {"2026-10-28T10:00:00Z", 0.25},
		"half an interval":     {"2026-10-28T11:30:00Z", 0.7071067811865476}, // 0.5^0.5
		"fractional seconds":   {"2026-10-28T10:00:00.000000Z", 0.25},
		"offset instant":       {"2026-10-28T12:00:00+02:00", 0.25}, // 10:00Z
		"future clamps to now": {"2026-10-28T13:00:00Z", 1},
		"unparsable":           {"garbage", 1},
		"missing":              {"", 1},
	} {
		if got := a.Weight(tc.completedAt); math.Abs(got-tc.want) > 1e-12 {
			t.Errorf("%s: Weight(%q) = %v, want %v", name, tc.completedAt, got, tc.want)
		}
	}
	// Production defaults: 0.97 every 15 minutes, a day old: 0.97^96 = 0.05371.
	prod := Aging{Factor: 0.97, Interval: 15 * time.Minute, Now: func() time.Time { return agingNow }}
	if got := prod.Weight("2026-10-27T12:00:00Z"); math.Abs(got-0.05371) > 1e-5 {
		t.Errorf("one day with the defaults = %v, want 0.05371", got)
	}
	// Disabled (zero value or out-of-range settings) never weighs anything down.
	for _, off := range []Aging{{}, {Factor: 1, Interval: time.Hour}, {Factor: 0.5}} {
		if got := off.Weight("2000-01-01T00:00:00Z"); got != 1 {
			t.Errorf("%+v weight = %v, want 1", off, got)
		}
	}
	// The default clock is the real one: an event from this instant is unweighted.
	if got := (Aging{Factor: 0.5, Interval: time.Hour}).Weight(time.Now().Format(time.RFC3339Nano)); got < 0.999 {
		t.Errorf("real clock weight = %v", got)
	}
}

func TestApplyAgedWeightsOnlyHot(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{OrderID: "o-old", UserID: "u", CompletedAt: "2026-10-28T10:00:00Z", // two intervals: w = 0.25
		Items: []Item{{SKU: "A", Quantity: 4}, {SKU: "B", Quantity: 2}}}
	if err := ApplyAged(context.Background(), rdb, ev, DefaultDedupeTTL, halfHourly()); err != nil {
		t.Fatal(err)
	}
	// hot: 4*0.25 = 1 and 2*0.25 = 0.5; everything else is the full, unweighted count.
	for sku, want := range map[string]float64{"A": 1, "B": 0.5} {
		if got, _ := mr.ZScore("hot:global", sku); math.Abs(got-want) > 1e-9 {
			t.Errorf("hot %s = %v, want %v", sku, got, want)
		}
	}
	if got, _ := mr.ZScore("user:u:skus", "A"); got != 4 {
		t.Errorf("user A = %v, want 4", got)
	}
	if got, _ := mr.ZScore("sku:A:buyers", "u"); got != 4 {
		t.Errorf("buyers A = %v, want 4", got)
	}
	if got, _ := mr.ZScore("cooccur:A", "B"); got != 1 {
		t.Errorf("cooccur = %v, want 1", got)
	}
	if mr.HGet("sku:count", "A") != "1" || mr.HGet("sku:count", "B") != "1" {
		t.Errorf("sku:count A=%q B=%q", mr.HGet("sku:count", "A"), mr.HGet("sku:count", "B"))
	}
}

func TestApplyAgedFreshEventEqualsApply(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{OrderID: "o-new", UserID: "u", CompletedAt: "2026-10-28T12:00:00Z", Items: []Item{{SKU: "A", Quantity: 3}}}
	if err := ApplyAged(context.Background(), rdb, ev, DefaultDedupeTTL, halfHourly()); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("hot:global", "A"); got != 3 {
		t.Errorf("hot A = %v, want 3 (age 0)", got)
	}
}

// Past the point where the weight falls under the removal threshold (0.5^7 = 0.0078 < 0.01), an
// event adds nothing to hot:global (the next decay round would delete it) but still feeds the rest
// of the model.
func TestApplyAgedAncientEventSkipsHotOnly(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{OrderID: "o-ancient", UserID: "u", CompletedAt: "2026-10-28T05:00:00Z", // 7 intervals
		Items: []Item{{SKU: "A", Quantity: 1000}, {SKU: "B", Quantity: 1}}}
	if err := ApplyAged(context.Background(), rdb, ev, DefaultDedupeTTL, halfHourly()); err != nil {
		t.Fatal(err)
	}
	if mr.Exists("hot:global") {
		t.Errorf("hot:global written for an event under the floor: %v", mr.Keys())
	}
	if got, _ := mr.ZScore("user:u:skus", "A"); got != 1000 {
		t.Errorf("user A = %v", got)
	}
	if got, _ := mr.ZScore("cooccur:A", "B"); got != 1 {
		t.Errorf("cooccur = %v", got)
	}
	if mr.HGet("sku:count", "A") != "1" || !mr.Exists("processed:o-ancient") {
		t.Errorf("count/marker missing: %v", mr.Keys())
	}
	// Just inside the floor (6 intervals: 0.5^6 = 0.015625) it still counts.
	ev = OrderCompleted{OrderID: "o-edge", CompletedAt: "2026-10-28T06:00:00Z", Items: []Item{{SKU: "C", Quantity: 64}}}
	if err := ApplyAged(context.Background(), rdb, ev, DefaultDedupeTTL, halfHourly()); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("hot:global", "C"); math.Abs(got-1) > 1e-9 { // 64 * 0.015625
		t.Errorf("hot C = %v, want 1", got)
	}
}

func TestApplyAgedWithoutOrderIDAndRedeliveryStayConsistent(t *testing.T) {
	mr, rdb := newRedis(t)
	ctx := context.Background()
	// No id: the MULTI/EXEC path weights and counts too.
	anon := OrderCompleted{CompletedAt: "2026-10-28T11:00:00Z", Items: []Item{{SKU: "A", Quantity: 2}, {SKU: "B", Quantity: 2}}}
	if err := ApplyAged(ctx, rdb, anon, 0, halfHourly()); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("hot:global", "A"); got != 1 {
		t.Errorf("hot A = %v, want 1", got)
	}
	if mr.HGet("sku:count", "A") != "1" {
		t.Errorf("sku:count A = %q", mr.HGet("sku:count", "A"))
	}
	// A redelivered id must not move the weighted hot score nor the order count a second time.
	ev := OrderCompleted{OrderID: "o-dup", CompletedAt: "2026-10-28T11:00:00Z", Items: []Item{{SKU: "A", Quantity: 2}}}
	for range 3 {
		if err := ApplyAged(ctx, rdb, ev, DefaultDedupeTTL, halfHourly()); err != nil {
			t.Fatal(err)
		}
	}
	if got, _ := mr.ZScore("hot:global", "A"); got != 2 { // 1 + 2*0.5, once
		t.Errorf("hot A = %v, want 2", got)
	}
	if mr.HGet("sku:count", "A") != "2" {
		t.Errorf("sku:count A = %q, want 2", mr.HGet("sku:count", "A"))
	}
}

func TestApplyCountsEachOrderOncePerSKU(t *testing.T) {
	mr, rdb := newRedis(t)
	// Two lines of A in one order are one order containing A.
	ev := OrderCompleted{OrderID: "o-1", Items: []Item{{SKU: "A", Quantity: 1}, {SKU: "A", Quantity: 5}, {SKU: "B", Quantity: 1}}}
	for _, id := range []string{"o-1", "o-2"} {
		ev.OrderID = id
		if err := Apply(context.Background(), rdb, ev, DefaultDedupeTTL); err != nil {
			t.Fatal(err)
		}
	}
	if mr.HGet("sku:count", "A") != "2" || mr.HGet("sku:count", "B") != "2" {
		t.Errorf("sku:count A=%q B=%q, want 2 and 2", mr.HGet("sku:count", "A"), mr.HGet("sku:count", "B"))
	}
}
