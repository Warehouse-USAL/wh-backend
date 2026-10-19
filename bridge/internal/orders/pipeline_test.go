package orders_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"strings"
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
	mu          sync.Mutex
	messages    []string
	publishes   []string
	processings []string
}

func (r *fakeRecorder) Publish(
	flow string,
	result string,
	_ time.Duration,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.publishes = append(r.publishes, flow+":"+result)
}

func (r *fakeRecorder) Processing(
	flow string,
	result string,
	_ time.Duration,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.processings = append(r.processings, flow+":"+result)
}

func (r *fakeRecorder) durations() ([]string, []string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.publishes...), append([]string(nil), r.processings...)
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
	return runLogging(t, consumer, publisher, want, ids, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func runLogging(
	t *testing.T,
	consumer *fakeConsumer,
	publisher *fakePublisher,
	want int,
	ids *requestid.Store,
	logger *slog.Logger,
) *fakeRecorder {
	rec := &fakeRecorder{}
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

// waitFor polls cond until it holds, failing the test after a deadline.
func waitFor(
	t *testing.T,
	cond func() bool,
) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met before the deadline")
		}
		time.Sleep(time.Millisecond)
	}
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
	publishes, processings := rec.durations()
	assertResults(t, publishes, "orders_out:ok")
	assertResults(t, processings, "orders_out:ok")
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
	publishes, processings := rec.durations()
	assertResults(t, publishes, "orders_out:error", "orders_out:error", "orders_out:error", "orders_out:ok")
	assertResults(t, processings, "orders_out:ok")
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
	_, processings := rec.durations()
	assertResults(t, processings, "orders_out:dropped", "orders_out:dropped", "orders_out:ok")
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
	waitFor(t, func() bool { return len(rec.results()) > 0 })
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
	_, processings := rec.durations()
	assertResults(t, processings, "orders_out:error")
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

func TestRedeliveryWithoutRequestIDKeepsTheRememberedOne(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{idRecord{fakeRecord{pending}, "req-1"}, fakeRecord{cancelled}}}
	publisher := &fakePublisher{order: &order}
	ids := requestid.New()
	runWith(t, consumer, publisher, 2, ids)
	if got := ids.Lookup("ORD-1"); got != "req-1" {
		t.Fatalf("remembered %q, want req-1 kept", got)
	}
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(
	p []byte,
) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

// count returns how many log lines were written so far.
func (b *syncBuffer) count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return bytes.Count(b.buf.Bytes(), []byte("\n"))
}

// lines decodes every JSON log line written so far.
func (b *syncBuffer) lines(
	t *testing.T,
) []map[string]any {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []map[string]any
	for _, raw := range bytes.Split(bytes.TrimSpace(b.buf.Bytes()), []byte("\n")) {
		var line map[string]any
		if err := json.Unmarshal(raw, &line); err != nil {
			t.Fatalf("log line %q: %v", raw, err)
		}
		out = append(out, line)
	}
	return out
}

func assertTrail(
	t *testing.T,
	lines []map[string]any,
	want ...string,
) {
	t.Helper()
	if len(lines) != len(want) {
		t.Fatalf("logged %v, want messages %v", lines, want)
	}
	for i, line := range lines {
		if line["msg"] != want[i] || line["request_id"] != "req-1" || line["order_id"] != "ORD-1" {
			t.Fatalf("line %d is %v, want %q with request_id req-1 and order_id ORD-1", i, line, want[i])
		}
		if _, dumped := line["payload"]; dumped {
			t.Fatalf("line %d dumps the payload: %v", i, line)
		}
	}
}

func TestEveryLineOfAnOrderCarriesItsRequestID(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{idRecord{fakeRecord{pending}, "req-1"}}}
	publisher := &fakePublisher{failures: 2, order: &order}
	logs := &syncBuffer{}
	runLogging(t, consumer, publisher, 1, requestid.New(), slog.New(slog.NewJSONHandler(logs, nil)))
	assertTrail(t, logs.lines(t),
		"order consumed from kafka",
		"rabbitmq publish failed, retrying",
		"rabbitmq publish failed, retrying",
		"order published to infra",
	)
}

func TestStoppingBeforePublishingIsLoggedWithTheRequestID(
	t *testing.T,
) {
	var order []string
	consumer := &fakeConsumer{records: []orders.Record{idRecord{fakeRecord{pending}, "req-1"}}}
	publisher := &fakePublisher{failures: 1 << 30, order: &order}
	logs := &syncBuffer{}
	logger := slog.New(slog.NewJSONHandler(logs, nil))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- orders.New(consumer, publisher, fastPolicy, logger, &fakeRecorder{}, requestid.New()).Run(ctx)
	}()
	waitFor(t, func() bool { return logs.count() >= 2 })
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v", err)
	}
	lines := logs.lines(t)
	if len(lines) < 3 {
		t.Fatalf("logged %v, want the consumption, a retry and the stop", lines)
	}
	last := lines[len(lines)-1]
	if last["msg"] != "stopping with an order unpublished" || last["request_id"] != "req-1" || last["order_id"] != "ORD-1" {
		t.Fatalf("last line %v, want the stop with request_id req-1", last)
	}
	assertTrail(t, lines[:2], "order consumed from kafka", "rabbitmq publish failed, retrying")
}

func TestSkippedRecordIsLoggedWithoutItsPayload(
	t *testing.T,
) {
	var order []string
	value := `{"status":"PII-MARKER"}`
	consumer := &fakeConsumer{records: []orders.Record{fakeRecord{value}}}
	logs := &syncBuffer{}
	runLogging(t, consumer, &fakePublisher{order: &order}, 1, requestid.New(), slog.New(slog.NewJSONHandler(logs, nil)))
	lines := logs.lines(t)
	if len(lines) != 1 || lines[0]["msg"] != "skipping record without a valid order_id" ||
		lines[0]["payload_bytes"] != float64(len(value)) {
		t.Fatalf("logged %v, want the size only", lines)
	}
	if strings.Contains(logs.buf.String(), "PII-MARKER") {
		t.Fatalf("log leaks the payload: %s", logs.buf.String())
	}
}
