package ingest

import (
	"context"
	"errors"
	"testing"

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
	if err := Apply(context.Background(), rdb, ev); err != nil {
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
	// A second delivery counts again (at-least-once, documented as harmless).
	if err := Apply(context.Background(), rdb, ev); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("cooccur:TEC-002", "TEC-004"); got != 2 {
		t.Errorf("replay cooccur = %v, want 2", got)
	}
}

func TestApplyMergesRepeatedSKUsAndSkipsJunk(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{UserID: "u", Items: []Item{
		{SKU: "A", Quantity: 1}, {SKU: "A", Quantity: 2}, {SKU: "", Quantity: 5}, {SKU: "B", Quantity: 0}, {SKU: "C", Quantity: -1},
	}}
	if err := Apply(context.Background(), rdb, ev); err != nil {
		t.Fatal(err)
	}
	if got, _ := mr.ZScore("user:u:skus", "A"); got != 3 {
		t.Errorf("A = %v, want 3", got)
	}
	// A single distinct SKU has no pair.
	if mr.Exists("cooccur:A") || mr.Exists("user:u:skus") == false || len(mr.Keys()) != 3 {
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyWithoutUserKeepsHotAndPairs(t *testing.T) {
	mr, rdb := newRedis(t)
	ev := OrderCompleted{Items: []Item{{SKU: "A", Quantity: 1}, {SKU: "B", Quantity: 1}}}
	if err := Apply(context.Background(), rdb, ev); err != nil {
		t.Fatal(err)
	}
	if !mr.Exists("hot:global") || !mr.Exists("cooccur:A") || mr.Exists("sku:A:buyers") {
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyEmptyOrderIsNoop(t *testing.T) {
	mr, rdb := newRedis(t)
	if err := Apply(context.Background(), rdb, OrderCompleted{OrderID: "o"}); err != nil {
		t.Fatal(err)
	}
	if len(mr.Keys()) != 0 {
		t.Errorf("keys = %v", mr.Keys())
	}
}

func TestApplyRedisDown(t *testing.T) {
	mr, rdb := newRedis(t)
	mr.Close()
	err := Apply(context.Background(), rdb, OrderCompleted{OrderID: "o9", Items: []Item{{SKU: "A", Quantity: 1}}})
	if err == nil {
		t.Fatal("want error")
	}
}

func TestApplyCapsAbsurdQuantity(t *testing.T) {
	_, rdb := newRedis(t)
	ev := OrderCompleted{UserID: "u", Items: []Item{{SKU: "A", Quantity: 2147483647}}}
	if err := Apply(context.Background(), rdb, ev); err != nil {
		t.Fatal(err)
	}
	if got := rdb.ZScore(context.Background(), "hot:global", "A").Val(); got != maxLineQuantity {
		t.Fatalf("hot score = %v, want %d", got, maxLineQuantity)
	}
}
