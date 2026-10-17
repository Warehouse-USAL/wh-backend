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
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

var fastPolicy = retry.Policy{Min: time.Millisecond, Max: 2 * time.Millisecond}

type fakeRecord struct {
	value string
}

func (r fakeRecord) Value() []byte {
	return []byte(r.value)
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
	order     *[]string
}

func (p *fakePublisher) Publish(
	_ context.Context,
	body []byte,
) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failures > 0 {
		p.failures--
		return errors.New("broker unavailable")
	}
	p.published = append(p.published, string(body))
	*p.order = append(*p.order, "publish:"+string(body))
	return nil
}

func (p *fakePublisher) bodies() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.published...)
}

func run(
	t *testing.T,
	consumer *fakeConsumer,
	publisher *fakePublisher,
	want int,
) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- orders.New(consumer, publisher, fastPolicy, logger).Run(ctx) }()
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
}

const pending = `{"order_id":"ORD-1","status":"pending","stops":["A","B"],"destination":"AREA-B"}`
const cancelled = `{"order_id":"ORD-1","status":"cancelled","stops":["A","B"],"destination":"AREA-B"}`

func TestOrderIsPublishedUnchangedThenCommitted(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{order: &order}
	run(t, consumer, publisher, 1)
	if got := publisher.bodies(); len(got) != 1 || got[0] != pending {
		t.Fatalf("published %v", got)
	}
}

func TestPublishFailureIsRetriedBeforeCommitting(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{failures: 3, order: &order}
	run(t, consumer, publisher, 1)
	if got := publisher.bodies(); len(got) != 1 {
		t.Fatalf("published %v", got)
	}
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
	run(t, consumer, publisher, 3)
	if got := publisher.bodies(); len(got) != 1 || got[0] != pending {
		t.Fatalf("published %v", got)
	}
}

func TestRecordIsNotCommittedWhenStoppedBeforePublishing(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{pending}}}
	publisher := &fakePublisher{failures: 1 << 30, order: &order}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- orders.New(consumer, publisher, fastPolicy, logger).Run(ctx) }()
	time.Sleep(20 * time.Millisecond)
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v, want nil on a clean stop", err)
	}
	if got := consumer.commits(); len(got) != 0 {
		t.Fatalf("committed %v without publishing", got)
	}
}
