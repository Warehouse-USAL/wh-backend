package fakeinfra_test

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

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/fakeinfra"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

var clock = time.Date(2026, 10, 16, 10, 0, 0, 0, time.UTC)

type summary struct {
	Kind   string
	Status string
	QR     string
}

func summarize(
	t *testing.T,
	steps []fakeinfra.Step,
) []summary {
	out := make([]summary, 0, len(steps))
	for _, step := range steps {
		out = append(out, describe(t, step.Body))
	}
	return out
}

func describe(
	t *testing.T,
	body []byte,
) summary {
	req, err := classify.Classify(body)
	if err != nil {
		t.Fatalf("fake infra produced an unclassifiable message %s: %v", body, err)
	}
	var fields struct {
		Status string  `json:"status"`
		LastQR *string `json:"last_qr"`
	}
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	qr := ""
	if fields.LastQR != nil {
		qr = *fields.LastQR
	}
	return summary{Kind: req.Kind, Status: fields.Status, QR: qr}
}

func TestScriptForPendingOrderWalksEveryStop(
	t *testing.T,
) {
	order := []byte(`{"order_id":"ORD-1","status":"pending","stops":["S1","S2"],"destination":"AREA-B"}`)
	steps, err := fakeinfra.Script(order, "VHC-009", clock)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := []summary{
		{classify.KindOrderStatus, "in_progress", ""},
		{classify.KindVehicleTelemetry, "busy", "S1"},
		{classify.KindVehicleTelemetry, "busy", "S1"},
		{classify.KindVehicleTelemetry, "busy", "S2"},
		{classify.KindOrderStatus, "completed", ""},
		{classify.KindVehicleTelemetry, "idle", ""},
	}
	got := summarize(t, steps)
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("step %d = %v, want %v", i, got[i], want[i])
		}
	}
}

func TestScriptUsesTheGivenVehicleAndOrder(
	t *testing.T,
) {
	order := []byte(`{"order_id":"ORD-7","status":"pending","stops":["S1"]}`)
	steps, err := fakeinfra.Script(order, "VHC-009", clock)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	var status struct {
		OrderID   string `json:"order_id"`
		VehicleID string `json:"vehicle_id"`
		Timestamp string `json:"timestamp"`
	}
	if err := json.Unmarshal(steps[0].Body, &status); err != nil {
		t.Fatal(err)
	}
	if status.OrderID != "ORD-7" || status.VehicleID != "VHC-009" || status.Timestamp != "2026-10-16T10:00:00Z" {
		t.Fatalf("got %+v", status)
	}
}

func TestScriptForCancelledOrderOnlyFreesTheVehicle(
	t *testing.T,
) {
	order := []byte(`{"order_id":"ORD-1","status":"cancelled"}`)
	steps, err := fakeinfra.Script(order, "VHC-001", clock)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	got := summarize(t, steps)
	if len(got) != 1 || got[0] != (summary{classify.KindVehicleTelemetry, "idle", ""}) {
		t.Fatalf("got %v", got)
	}
}

func TestScriptRejectsGarbage(
	t *testing.T,
) {
	if _, err := fakeinfra.Script([]byte(`nope`), "VHC-001", clock); err == nil {
		t.Fatal("expected an error")
	}
	if _, err := fakeinfra.Script([]byte(`{"status":"pending"}`), "VHC-001", clock); !errors.Is(err, fakeinfra.ErrNoOrderID) {
		t.Fatalf("got %v, want ErrNoOrderID for a missing order_id", err)
	}
}

type fakeDelivery struct {
	body  string
	acked chan struct{}
}

func (d *fakeDelivery) Body() []byte { return []byte(d.body) }
func (d *fakeDelivery) Ack() error   { close(d.acked); return nil }
func (d *fakeDelivery) Requeue() error {
	return nil
}

type fakeSource struct {
	deliveries chan vehicles.Delivery
}

func (s *fakeSource) Deliveries(
	_ context.Context,
) <-chan vehicles.Delivery {
	return s.deliveries
}

type recordingPublisher struct {
	mu     sync.Mutex
	bodies []string
}

func (p *recordingPublisher) Publish(
	_ context.Context,
	body []byte,
) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.bodies = append(p.bodies, string(body))
	return nil
}

func (p *recordingPublisher) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.bodies)
}

func TestRunPublishesTheScriptAndAcksTheOrder(
	t *testing.T,
) {
	source := &fakeSource{deliveries: make(chan vehicles.Delivery, 1)}
	publisher := &recordingPublisher{}
	order := &fakeDelivery{
		body:  `{"order_id":"ORD-1","status":"pending","stops":["S1"]}`,
		acked: make(chan struct{}),
	}
	source.deliveries <- order
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	done := make(chan error, 1)
	go func() { done <- fakeinfra.Run(ctx, source, publisher, "VHC-001", time.Millisecond, logger) }()
	select {
	case <-order.acked:
	case <-time.After(2 * time.Second):
		t.Fatal("order was never acked")
	}
	if publisher.count() != 5 {
		t.Fatalf("published %d messages, want 5", publisher.count())
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v", err)
	}
}

func TestLogsCarryPayloadSizesNeverPayloads(
	t *testing.T,
) {
	source := &fakeSource{deliveries: make(chan vehicles.Delivery, 2)}
	good := &fakeDelivery{body: `{"order_id":"ORD-1","status":"pending","stops":["PII-MARKER"]}`, acked: make(chan struct{})}
	bad := &fakeDelivery{body: `PII-MARKER {`, acked: make(chan struct{})}
	noID := &fakeDelivery{body: `{"status":"PII-MARKER"}`, acked: make(chan struct{})}
	source.deliveries = make(chan vehicles.Delivery, 3)
	source.deliveries <- bad
	source.deliveries <- noID
	source.deliveries <- good
	logs := &lockedBuffer{}
	logger := slog.New(slog.NewTextHandler(logs, nil))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- fakeinfra.Run(ctx, source, &recordingPublisher{}, "VHC-001", time.Millisecond, logger) }()
	for _, d := range []*fakeDelivery{bad, noID, good} {
		select {
		case <-d.acked:
		case <-time.After(2 * time.Second):
			t.Fatal("delivery was never acked")
		}
	}
	cancel()
	<-done
	if got := logs.String(); strings.Contains(got, "PII-MARKER") || !strings.Contains(got, "payload_bytes=") {
		t.Fatalf("logs must carry sizes and no payload: %s", got)
	}
	if got := logs.String(); !strings.Contains(got, `reason="malformed order"`) || !strings.Contains(got, `reason="no order_id"`) {
		t.Fatalf("ignored orders must carry a static reason: %s", got)
	}
}

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(
	p []byte,
) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
