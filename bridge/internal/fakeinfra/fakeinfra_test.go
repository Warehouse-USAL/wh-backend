package fakeinfra_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/fakeinfra"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

type delivery struct{ body string }

func (d delivery) Body() []byte   { return []byte(d.body) }
func (d delivery) Ack() error     { return nil }
func (d delivery) Requeue() error { return nil }

type source struct{ ch chan vehicles.Delivery }

func (s source) Deliveries(
	_ context.Context,
) <-chan vehicles.Delivery {
	return s.ch
}

type recorder struct {
	mu     sync.Mutex
	bodies []map[string]any
}

func (r *recorder) Publish(
	_ context.Context,
	body []byte,
) error {
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		return err
	}
	r.mu.Lock()
	r.bodies = append(r.bodies, m)
	r.mu.Unlock()
	return nil
}

// events lists "status:<order status>" and "tel:<vehicle status>" in publish order.
func (r *recorder) events() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]string, 0, len(r.bodies))
	for _, b := range r.bodies {
		if _, ok := b["order_id"]; ok {
			out = append(out, "order:"+b["order_id"].(string)+":"+b["status"].(string))
		} else {
			out = append(out, "tel:"+b["status"].(string))
		}
	}
	return out
}

type harness struct {
	orders  chan vehicles.Delivery
	control chan vehicles.Delivery
	rec     *recorder
	cancel  context.CancelFunc
	done    chan error
}

func start(
	t *testing.T,
	cfg fakeinfra.Config,
) *harness {
	t.Helper()
	h := &harness{
		orders: make(chan vehicles.Delivery, 16), control: make(chan vehicles.Delivery, 4),
		rec: &recorder{}, done: make(chan error, 1),
	}
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	cfg.VehicleID = "VHC-T"
	go func() {
		h.done <- fakeinfra.Run(ctx, source{h.orders}, source{h.control}, h.rec, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	}()
	t.Cleanup(func() { h.stop(t) })
	return h
}

func (h *harness) stop(
	t *testing.T,
) {
	t.Helper()
	h.cancel()
	select {
	case err := <-h.done:
		if err != nil {
			t.Errorf("Run returned %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Error("Run did not stop")
	}
	h.done <- nil
}

func (h *harness) order(
	body string,
) {
	h.orders <- delivery{body}
}

func (h *harness) waitFor(
	t *testing.T,
	want string,
) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		for _, e := range h.rec.events() {
			if e == want {
				return
			}
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("never saw %q, got %v", want, h.rec.events())
}

func (h *harness) count(
	want string,
) int {
	n := 0
	for _, e := range h.rec.events() {
		if e == want {
			n++
		}
	}
	return n
}

func TestOrderRunsToCompletionAndVehicleReturnsToIdle(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond})
	h.order(`{"order_id":"A","status":"pending","stops":["S1","S2"]}`)
	h.waitFor(t, "order:A:completed")
	time.Sleep(20 * time.Millisecond)
	ev := h.rec.events()
	if ev[0] != "tel:idle" || ev[1] != "order:A:in_progress" || ev[len(ev)-1] != "tel:idle" {
		t.Fatalf("unexpected sequence %v", ev)
	}
}

func TestCancelOfOrderOnTheRoadReportsNothingAndFreesTheVehicleAfterTheWayBack(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 40 * time.Millisecond})
	h.order(`{"order_id":"A","status":"pending","stops":["S1","S2","S3"]}`)
	h.waitFor(t, "order:A:in_progress")
	time.Sleep(60 * time.Millisecond)
	h.order(`{"order_id":"A","status":"cancelled"}`)
	deadline := time.Now().Add(3 * time.Second)
	for h.rec.events()[len(h.rec.events())-1] != "tel:idle" && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	time.Sleep(60 * time.Millisecond)
	if h.count("order:A:completed")+h.count("order:A:cancelled") != 0 {
		t.Fatalf("a cancelled order must not be reported: %v", h.rec.events())
	}
	if last := h.rec.events()[len(h.rec.events())-1]; last != "tel:idle" {
		t.Fatalf("vehicle never went back to idle: %v", h.rec.events())
	}
}

func TestCancelledOrderInQueueIsNeverDispatched(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 30 * time.Millisecond})
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.order(`{"order_id":"B","status":"pending","stops":["S1"]}`)
	h.order(`{"order_id":"B","status":"cancelled"}`)
	h.waitFor(t, "order:A:completed")
	time.Sleep(100 * time.Millisecond)
	if h.count("order:B:in_progress") != 0 {
		t.Fatalf("B was dispatched after its cancel: %v", h.rec.events())
	}
}

func TestCancelArrivingBeforeTheOrderIgnoresTheOrder(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond})
	h.order(`{"order_id":"A","status":"cancelled"}`)
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.order(`{"order_id":"B","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:B:completed")
	if h.count("order:A:in_progress") != 0 {
		t.Fatalf("A ran although it was cancelled first: %v", h.rec.events())
	}
}

func TestOrderWithoutStopsIsReportedCancelledAndDuplicatesAreIgnored(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond})
	h.order(`{"order_id":"E","status":"pending","stops":[]}`)
	h.waitFor(t, "order:E:cancelled")
	h.order(`{"order_id":"D","status":"pending","stops":["S1"]}`)
	h.order(`{"order_id":"D","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:D:completed")
	time.Sleep(30 * time.Millisecond)
	if h.count("order:D:in_progress") != 1 {
		t.Fatalf("duplicate order ran twice: %v", h.rec.events())
	}
}

func TestDropCancelsLosesTheCancelSoTheOrderCompletes(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 20 * time.Millisecond, DropCancels: true})
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:A:in_progress")
	h.order(`{"order_id":"A","status":"cancelled"}`)
	h.waitFor(t, "order:A:completed")
}

func TestDuplicateStatusesPublishesEveryStatusTwice(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond, DuplicateStatuses: true})
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:A:completed")
	time.Sleep(20 * time.Millisecond)
	if h.count("order:A:in_progress") != 2 || h.count("order:A:completed") != 2 {
		t.Fatalf("got %v", h.rec.events())
	}
}

func TestStuckBusyNeverReportsIdleAgain(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond, StuckBusy: true})
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:A:completed")
	time.Sleep(30 * time.Millisecond)
	if last := h.rec.events()[len(h.rec.events())-1]; last != "tel:busy" {
		t.Fatalf("got %v", h.rec.events())
	}
}

func TestControlFlipsModesAndRestartForgetsTheOrderOnTheRoad(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 40 * time.Millisecond, TelemetryEvery: 30 * time.Millisecond})
	h.control <- delivery{`{"drop_cancels":true,"duplicate_statuses":false,"stuck_busy":false,"step_delay":"10ms"}`}
	h.control <- delivery{`not json`}
	h.order(`{"order_id":"A","status":"pending","stops":["S1","S2"]}`)
	h.waitFor(t, "order:A:in_progress")
	h.control <- delivery{`{"restart":true}`}
	time.Sleep(150 * time.Millisecond)
	if h.count("order:A:completed") != 0 {
		t.Fatalf("a restarted central must forget the order: %v", h.rec.events())
	}
	h.order(`{"order_id":"B","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:B:completed")
}

func TestMalformedAndAnonymousOrdersAreIgnored(
	t *testing.T,
) {
	h := start(t, fakeinfra.Config{StepDelay: 5 * time.Millisecond})
	h.order(`nope`)
	h.order(`{"status":"pending"}`)
	h.order(`{"order_id":"A","status":"pending","stops":["S1"]}`)
	h.waitFor(t, "order:A:completed")
}
