package vehicles_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

var fastPolicy = retry.Policy{Min: time.Millisecond, Max: 2 * time.Millisecond}

type fakeDelivery struct {
	body     string
	mu       sync.Mutex
	acked    bool
	requeued bool
	settled  chan struct{}
	once     sync.Once
}

func newDelivery(
	body string,
) *fakeDelivery {
	return &fakeDelivery{body: body, settled: make(chan struct{})}
}

func (d *fakeDelivery) Body() []byte {
	return []byte(d.body)
}

func (d *fakeDelivery) Ack() error {
	d.mu.Lock()
	d.acked = true
	d.mu.Unlock()
	d.once.Do(func() { close(d.settled) })
	return nil
}

func (d *fakeDelivery) Requeue() error {
	d.mu.Lock()
	d.requeued = true
	d.mu.Unlock()
	d.once.Do(func() { close(d.settled) })
	return nil
}

func (d *fakeDelivery) state() (bool, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.acked, d.requeued
}

type fakeSource struct {
	deliveries chan vehicles.Delivery
}

func (s *fakeSource) Deliveries(
	_ context.Context,
) <-chan vehicles.Delivery {
	return s.deliveries
}

type scriptedClient struct {
	mu      sync.Mutex
	results []error
	sent    []classify.Request
}

func (c *scriptedClient) Send(
	_ context.Context,
	req classify.Request,
) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sent = append(c.sent, req)
	if len(c.results) == 0 {
		return nil
	}
	err := c.results[0]
	c.results = c.results[1:]
	return err
}

func (c *scriptedClient) paths() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	paths := make([]string, 0, len(c.sent))
	for _, req := range c.sent {
		paths = append(paths, req.Path)
	}
	return paths
}

type harness struct {
	source *fakeSource
	client *scriptedClient
	cancel context.CancelFunc
	done   chan error
}

func start(
	t *testing.T,
	results ...error,
) *harness {
	h := &harness{
		source: &fakeSource{deliveries: make(chan vehicles.Delivery)},
		client: &scriptedClient{results: results},
		done:   make(chan error, 1),
	}
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	pipeline := vehicles.New(h.source, h.client, fastPolicy, logger)
	go func() { h.done <- pipeline.Run(ctx) }()
	t.Cleanup(cancel)
	return h
}

func (h *harness) deliver(
	t *testing.T,
	d *fakeDelivery,
) {
	select {
	case h.source.deliveries <- d:
	case <-time.After(2 * time.Second):
		t.Fatal("pipeline did not take the delivery")
	}
}

func (h *harness) waitSettled(
	t *testing.T,
	d *fakeDelivery,
) {
	select {
	case <-d.settled:
	case <-time.After(2 * time.Second):
		t.Fatal("delivery was neither acked nor requeued")
	}
}

const statusMsg = `{"order_id":"ORD-1","status":"in_progress","vehicle_id":"VHC-001","timestamp":"t"}`
const telemetryMsg = `{"vehicle_id":"VHC-001","status":"idle","battery":90,"last_qr":null,"error":null,"timestamp":"t"}`

func TestDeliveryIsAckedAfterBackendAccepts(
	t *testing.T,
) {
	h := start(t)
	d := newDelivery(statusMsg)
	h.deliver(t, d)
	h.waitSettled(t, d)
	acked, requeued := d.state()
	if !acked || requeued {
		t.Fatalf("acked=%v requeued=%v", acked, requeued)
	}
	if got := h.client.paths(); len(got) != 1 || got[0] != "/infra/orders/ORD-1/status" {
		t.Fatalf("sent %v", got)
	}
}

func TestTransientFailureIsRetriedUntilItSucceeds(
	t *testing.T,
) {
	h := start(t, errors.New("503"), errors.New("timeout"))
	d := newDelivery(telemetryMsg)
	h.deliver(t, d)
	h.waitSettled(t, d)
	acked, _ := d.state()
	if !acked {
		t.Fatal("delivery was not acked after the retries")
	}
	if got := h.client.paths(); len(got) != 3 {
		t.Fatalf("expected 3 attempts, got %v", got)
	}
}

func TestRejectedMessageIsAckedWithoutRetry(
	t *testing.T,
) {
	h := start(t, &backend.RejectedError{Status: 404, Body: "VEHICLE_NOT_FOUND"})
	d := newDelivery(telemetryMsg)
	h.deliver(t, d)
	h.waitSettled(t, d)
	acked, requeued := d.state()
	if !acked || requeued {
		t.Fatalf("acked=%v requeued=%v", acked, requeued)
	}
	if got := h.client.paths(); len(got) != 1 {
		t.Fatalf("expected a single attempt, got %v", got)
	}
}

func TestUnclassifiableMessageIsAckedAndNeverSent(
	t *testing.T,
) {
	h := start(t)
	d := newDelivery(`{"status":"idle"}`)
	h.deliver(t, d)
	h.waitSettled(t, d)
	acked, _ := d.state()
	if !acked {
		t.Fatal("garbage must be acked so it does not block the queue")
	}
	if got := h.client.paths(); len(got) != 0 {
		t.Fatalf("nothing should reach the backend, got %v", got)
	}
}

func TestMessagesAreSentInArrivalOrder(
	t *testing.T,
) {
	h := start(t)
	first, second := newDelivery(statusMsg), newDelivery(telemetryMsg)
	h.deliver(t, first)
	h.deliver(t, second)
	h.waitSettled(t, second)
	got := h.client.paths()
	if len(got) != 2 || got[0] != "/infra/orders/ORD-1/status" || got[1] != "/infra/vehicles/VHC-001/telemetry" {
		t.Fatalf("sent %v", got)
	}
}

func TestCancellationRequeuesTheMessageInFlight(
	t *testing.T,
) {
	h := start(t, errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"))
	d := newDelivery(statusMsg)
	h.deliver(t, d)
	time.Sleep(10 * time.Millisecond)
	h.cancel()
	h.waitSettled(t, d)
	acked, requeued := d.state()
	if acked || !requeued {
		t.Fatalf("acked=%v requeued=%v", acked, requeued)
	}
	if err := <-h.done; err != nil {
		t.Fatalf("Run returned %v on cancellation", err)
	}
}

func TestRunStopsWhenSourceCloses(
	t *testing.T,
) {
	h := start(t)
	close(h.source.deliveries)
	select {
	case err := <-h.done:
		if err != nil {
			t.Fatalf("Run returned %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return after the source closed")
	}
}
