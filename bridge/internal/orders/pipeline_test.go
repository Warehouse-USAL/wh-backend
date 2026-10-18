package orders_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

var fastPolicy = retry.Policy{Min: time.Millisecond, Max: 2 * time.Millisecond}

type fakeRecord struct {
	value string
}

func (r fakeRecord) Value() []byte {
	return []byte(r.value)
}

func (r fakeRecord) RequestID() string {
	return ""
}

type idRecord struct {
	fakeRecord
	id string
}

func (r idRecord) RequestID() string {
	return r.id
}

type fakeConsumer struct {
	mu        sync.Mutex
	records   []orders.Record
	committed []string
}

func (c *fakeConsumer) Poll(
	ctx context.Context,
) (orders.Record, error) {
	c.mu.Lock()
	if len(c.records) > 0 {
		record := c.records[0]
		c.records = c.records[1:]
		c.mu.Unlock()
		return record, nil
	}
	c.mu.Unlock()
	<-ctx.Done()
	return nil, ctx.Err()
}

func (c *fakeConsumer) Commit(
	_ context.Context,
	record orders.Record,
) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.committed = append(c.committed, string(record.Value()))
	return nil
}

func (c *fakeConsumer) Close() {}

func (c *fakeConsumer) commits() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.committed...)
}

type fakePublisher struct {
	mu        sync.Mutex
	failures  int
	published []string
	ids       []string
	order     *[]string
}

func (p *fakePublisher) Publish(
	ctx context.Context,
	body []byte,
) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failures > 0 {
		p.failures--
		return errors.New("broker unavailable")
	}
	p.published = append(p.published, string(body))
	p.ids = append(p.ids, requestid.From(ctx))
	*p.order = append(*p.order, "publish:"+string(body))
	return nil
}

func (p *fakePublisher) bodies() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.published...)
}

type fakeRecorder struct {
	mu       sync.Mutex
	messages []string
}

func (r *fakeRecorder) Message(
	flow string,
	result string,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.messages = append(r.messages, flow+":"+result)
}

func (r *fakeRecorder) Callback(string, time.Duration) {}

func (r *fakeRecorder) Lag(string, int64) {}

func (r *fakeRecorder) results() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.messages...)
}

func run(
	t *testing.T,
	consumer *fakeConsumer,
	publisher *fakePublisher,
	want int,
) *fakeRecorder {
	return runWith(t, consumer, publisher, want, requestid.New())
}

func runWith(
	t *testing.T,
	consumer *fakeConsumer,
	publisher *fakePublisher,
	want int,
	ids *requestid.Store,
) *fakeRecorder {
	rec := &fakeRecorder{}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- orders.New(consumer, publisher, fastPolicy, logger, rec, ids).Run(ctx) }()
	deadline := time.After(2 * time.Second)
	for len(consumer.commits()) < want {
		select {
		case <-deadline:
			t.Fatalf("only %d commits after the deadline", len(consumer.commits()))
		case <-time.After(time.Millisecond):
		}
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v", err)
	}
	return rec
}

func assertResults(
	t *testing.T,
	got []string,
	want ...string,
) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("recorded %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("recorded %v, want %v", got, want)
		}
	}
}

const pending = `{"order_id":"ORD-1","status":"pending","stops":["A","B"],"destination":"AREA-B"}`
const cancelled = `{"order_id":"ORD-1","status":"cancelled","stops":["A","B"],"destination":"AREA-B"}`

func TestOrderIsPublishedUnchangedThenCommitted(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{order: &order}
	rec := run(t, consumer, publisher, 1)
	if got := publisher.bodies(); len(got) != 1 || got[0] != pending {
		t.Fatalf("published %v", got)
	}
	assertResults(t, rec.results(), "orders_out:ok")
}

func TestPublishFailureIsRetriedBeforeCommitting(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{failures: 3, order: &order}
	rec := run(t, consumer, publisher, 1)
	if got := publisher.bodies(); len(got) != 1 {
		t.Fatalf("published %v", got)
	}
	assertResults(t, rec.results(), "orders_out:retry", "orders_out:retry", "orders_out:retry", "orders_out:ok")
}

func TestOrdersKeepTheirOrder(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}, fakeRecord{cancelled}}}
	publisher := &fakePublisher{order: &order}
	run(t, consumer, publisher, 2)
	got := publisher.bodies()
	if len(got) != 2 || got[0] != pending || got[1] != cancelled {
		t.Fatalf("published %v", got)
	}
}

func TestInvalidRecordsAreSkippedButCommitted(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{
		fakeRecord{"not json"},
		fakeRecord{`{"status":"pending"}`},
		fakeRecord{pending},
	}}
	publisher := &fakePublisher{order: &order}
	rec := run(t, consumer, publisher, 3)
	if got := publisher.bodies(); len(got) != 1 || got[0] != pending {
		t.Fatalf("published %v", got)
	}
	assertResults(t, rec.results(), "orders_out:dropped", "orders_out:dropped", "orders_out:ok")
}

func TestRecordIsNotCommittedWhenStoppedBeforePublishing(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{failures: 1 << 30, order: &order}
	rec := &fakeRecorder{}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- orders.New(consumer, publisher, fastPolicy, logger, rec, requestid.New()).Run(ctx) }()
	time.Sleep(20 * time.Millisecond)
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v, want nil on a clean stop", err)
	}
	if got := consumer.commits(); len(got) != 0 {
		t.Fatalf("committed %v without publishing", got)
	}
	got := rec.results()
	if len(got) < 2 || got[0] != "orders_out:retry" || got[len(got)-1] != "orders_out:error" {
		t.Fatalf("recorded %v, want retries then orders_out:error", got)
	}
}

func TestRequestIDIsRememberedAndForwarded(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{idRecord{fakeRecord{pending}, "req-1"}}}
	publisher := &fakePublisher{order: &order}
	ids := requestid.New()
	runWith(t, consumer, publisher, 1, ids)
	if got := ids.Lookup("ORD-1"); got != "req-1" {
		t.Fatalf("remembered %q, want req-1", got)
	}
	publisher.mu.Lock()
	defer publisher.mu.Unlock()
	if len(publisher.ids) != 1 || publisher.ids[0] != "req-1" {
		t.Fatalf("published with ids %v", publisher.ids)
	}
}

func TestRecordWithoutRequestIDIsPublishedWithoutOne(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{order: &order}
	ids := requestid.New()
	runWith(t, consumer, publisher, 1, ids)
	if got := ids.Lookup("ORD-1"); got != "" {
		t.Fatalf("remembered %q, want nothing", got)
	}
	publisher.mu.Lock()
	defer publisher.mu.Unlock()
	if len(publisher.ids) != 1 || publisher.ids[0] != "" {
		t.Fatalf("published with ids %v", publisher.ids)
	}
}
