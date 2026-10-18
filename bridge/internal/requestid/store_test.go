package requestid

import (
	"context"
	"strconv"
	"sync"
	"testing"
	"time"
)

func TestStoreEvictsOldestBeyondCapacity(
	t *testing.T,
) {
	s := New()
	for i := range capacity + 1 {
		s.Remember("ORD-"+strconv.Itoa(i), "req-"+strconv.Itoa(i))
	}
	if got := s.Lookup("ORD-0"); got != "" {
		t.Fatalf("oldest entry survived: %q", got)
	}
	if got := s.Lookup("ORD-1"); got != "req-1" {
		t.Fatalf("ORD-1 = %q, want req-1", got)
	}
	if got := s.Lookup("ORD-" + strconv.Itoa(capacity)); got != "req-"+strconv.Itoa(capacity) {
		t.Fatalf("newest entry = %q", got)
	}
}

func TestLookupKeepsAnEntryFromBeingEvicted(
	t *testing.T,
) {
	s := New()
	for i := range capacity {
		s.Remember("ORD-"+strconv.Itoa(i), "req")
	}
	s.Lookup("ORD-0")
	s.Remember("ORD-new", "req")
	if s.Lookup("ORD-0") == "" {
		t.Fatal("recently used entry was evicted")
	}
	if s.Lookup("ORD-1") != "" {
		t.Fatal("least recently used entry survived")
	}
}

func TestStoreExpiresAfterTTL(
	t *testing.T,
) {
	now := time.Unix(0, 0)
	s := New()
	s.now = func() time.Time { return now }
	s.Remember("ORD-1", "req-1")
	now = now.Add(ttl - time.Second)
	if got := s.Lookup("ORD-1"); got != "req-1" {
		t.Fatalf("before ttl = %q", got)
	}
	now = now.Add(time.Second)
	if got := s.Lookup("ORD-1"); got != "" {
		t.Fatalf("after ttl = %q, want empty", got)
	}
}

func TestRememberReplacesAndRefreshes(
	t *testing.T,
) {
	now := time.Unix(0, 0)
	s := New()
	s.now = func() time.Time { return now }
	s.Remember("ORD-1", "req-1")
	now = now.Add(ttl - time.Second)
	s.Remember("ORD-1", "req-2")
	now = now.Add(time.Minute)
	if got := s.Lookup("ORD-1"); got != "req-2" {
		t.Fatalf("got %q, want req-2", got)
	}
}

func TestStoreIsSafeForConcurrentUse(
	t *testing.T,
) {
	s := New()
	var wg sync.WaitGroup
	for g := range 8 {
		wg.Go(func() {
			for i := range 1000 {
				id := "ORD-" + strconv.Itoa(g) + "-" + strconv.Itoa(i)
				s.Remember(id, "req")
				s.Lookup(id)
			}
		})
	}
	wg.Wait()
}

func TestValidAcceptsOnlySafeIDs(
	t *testing.T,
) {
	cases := map[string]bool{
		"req-123_ABC":            true,
		"":                       false,
		"evil\nvalue":            false,
		"with space":             false,
		"a/b":                    false,
		string(make([]byte, 65)): false,
	}
	long := ""
	for range 64 {
		long += "a"
	}
	cases[long] = true
	cases[long+"a"] = false
	for id, want := range cases {
		if got := Valid(id); got != want {
			t.Errorf("Valid(%q) = %v, want %v", id, got, want)
		}
	}
}

func TestContextCarriesTheID(
	t *testing.T,
) {
	if got := From(context.Background()); got != "" {
		t.Fatalf("empty context = %q", got)
	}
	if got := From(With(context.Background(), "req-1")); got != "req-1" {
		t.Fatalf("got %q", got)
	}
}
