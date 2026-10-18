package vehicles_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
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
	time.Sleep(50 * time.Microsecond)
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

type fakeRecorder struct {
	mu        sync.Mutex
	messages  []string
	callbacks []string
}

func (r *fakeRecorder) Message(
	flow string,
	result string,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.messages = append(r.messages, flow+":"+result)
}

func (r *fakeRecorder) Callback(
	flow string,
	d time.Duration,
) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d > 0 {
		r.callbacks = append(r.callbacks, flow)
	}
}

func (r *fakeRecorder) Lag(string, int64) {}

func (r *fakeRecorder) recorded() ([]string, []string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.messages...), append([]string(nil), r.callbacks...)
}

func equal(
	got []string,
	want ...string,
) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range want {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

type harness struct {
	source *fakeSource
	client *scriptedClient
	rec    *fakeRecorder
	cancel context.CancelFunc
	done   chan error
	logger *slog.Logger
}

func start(
	t *testing.T,
	results ...error,
) *harness {
	h := &harness{
		source: &fakeSource{deliveries: make(chan vehicles.Delivery)},
		client: &scriptedClient{results: results},
		rec:    &fakeRecorder{},
		done:   make(chan error, 1),
	}
	h.run(t, h.client, requestid.New())
	return h
}

func (h *harness) run(
	t *testing.T,
	client backend.Client,
	ids *requestid.Store,
) {
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	logger := h.logger
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	pipeline := vehicles.New(h.source, client, fastPolicy, logger, h.rec, ids)
	go func() { h.done <- pipeline.Run(ctx) }()
	t.Cleanup(cancel)
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

type recordingCase struct {
	name      string
	body      string
	results   []error
	messages  []string
	callbacks []string
}

func (c recordingCase) run(
	t *testing.T,
) {
	h := start(t, c.results...)
	d := newDelivery(c.body)
	h.deliver(t, d)
	h.waitSettled(t, d)
	messages, callbacks := h.rec.recorded()
	if !equal(messages, c.messages...) {
		t.Fatalf("messages %v, want %v", messages, c.messages)
	}
	if !equal(callbacks, c.callbacks...) {
		t.Fatalf("callbacks %v, want %v", callbacks, c.callbacks)
	}
}

func TestDeliveriesAreRecordedPerFlowAndAttempt(
	t *testing.T,
) {
	cases := []recordingCase{
		{
			name:      "status accepted",
			body:      statusMsg,
			messages:  []string{"orders_in:ok"},
			callbacks: []string{"orders_in"},
		},
		{
			name:      "telemetry 503 then 200",
			body:      telemetryMsg,
			results:   []error{errors.New("backend answered status 503")},
			messages:  []string{"vehicles_in:retry", "vehicles_in:ok"},
			callbacks: []string{"vehicles_in", "vehicles_in"},
		},
		{
			name:      "status 422",
			body:      statusMsg,
			results:   []error{&backend.RejectedError{Status: 422, Body: "INVALID"}},
			messages:  []string{"orders_in:dropped"},
			callbacks: []string{"orders_in"},
		},
		{
			name:     "unclassifiable",
			body:     `{"status":"idle"}`,
			messages: []string{"vehicles_in:dropped"},
		},
	}
	for _, c := range cases {
		t.Run(c.name, c.run)
	}
}

func TestCancellationWithAMessageInFlightRecordsAnError(
	t *testing.T,
) {
	h := start(t, errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"), errors.New("503"))
	d := newDelivery(telemetryMsg)
	h.deliver(t, d)
	time.Sleep(10 * time.Millisecond)
	h.cancel()
	h.waitSettled(t, d)
	messages, callbacks := h.rec.recorded()
	if len(messages) < 2 || messages[0] != "vehicles_in:retry" || messages[len(messages)-1] != "vehicles_in:error" {
		t.Fatalf("messages %v, want retries then vehicles_in:error", messages)
	}
	if len(callbacks) != len(messages)-1 {
		t.Fatalf("%d callbacks for %d attempts", len(callbacks), len(messages)-1)
	}
}

// callbackHeaders runs body through the pipeline against a real HTTP client
// and returns the X-Request-Id header the backend received, if any.
func callbackHeaders(
	t *testing.T,
	ids *requestid.Store,
	body string,
) []string {
	var mu sync.Mutex
	var got []string
	server := httptest.NewServer(http.HandlerFunc(func(
		w http.ResponseWriter,
		r *http.Request,
	) {
		mu.Lock()
		defer mu.Unlock()
		got = append(got, r.Header.Values("X-Request-Id")...)
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(server.Close)
	h := &harness{source: &fakeSource{deliveries: make(chan vehicles.Delivery)}, rec: &fakeRecorder{}, done: make(chan error, 1)}
	h.run(t, backend.New(server.URL, "secret", time.Second), ids)
	d := newDelivery(body)
	h.deliver(t, d)
	h.waitSettled(t, d)
	mu.Lock()
	defer mu.Unlock()
	return got
}

func TestCallbackCarriesRememberedID(
	t *testing.T,
) {
	ids := requestid.New()
	ids.Remember("ORD-1", "req-1")
	if got := callbackHeaders(t, ids, statusMsg); len(got) != 1 || got[0] != "req-1" {
		t.Fatalf("X-Request-Id %v, want [req-1]", got)
	}
}

func TestCallbackWithoutRememberedIDOmitsHeader(
	t *testing.T,
) {
	if got := callbackHeaders(t, requestid.New(), statusMsg); len(got) != 0 {
		t.Fatalf("X-Request-Id %v, want none", got)
	}
}

func TestTelemetryCallbackNeverCarriesAnID(
	t *testing.T,
) {
	ids := requestid.New()
	ids.Remember("VHC-001", "req-1")
	if got := callbackHeaders(t, ids, telemetryMsg); len(got) != 0 {
		t.Fatalf("X-Request-Id %v, want none", got)
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

func TestEveryLineOfAnOrderCallbackCarriesItsRequestID(
	t *testing.T,
) {
	ids := requestid.New()
	ids.Remember("ORD-1", "req-1")
	logs := &syncBuffer{}
	h := &harness{
		source: &fakeSource{deliveries: make(chan vehicles.Delivery)},
		client: &scriptedClient{results: []error{errors.New("503")}},
		rec:    &fakeRecorder{},
		done:   make(chan error, 1),
		logger: slog.New(slog.NewJSONHandler(logs, nil)),
	}
	h.run(t, h.client, ids)
	d := newDelivery(statusMsg)
	h.deliver(t, d)
	h.waitSettled(t, d)
	logs.mu.Lock()
	defer logs.mu.Unlock()
	var got []string
	for _, raw := range bytes.Split(bytes.TrimSpace(logs.buf.Bytes()), []byte("\n")) {
		var line map[string]any
		if err := json.Unmarshal(raw, &line); err != nil {
			t.Fatalf("log line %q: %v", raw, err)
		}
		if line["request_id"] != "req-1" {
			t.Fatalf("line %v has no request_id req-1", line)
		}
		got = append(got, line["msg"].(string))
	}
	if !equal(got, "backend unavailable, retrying", "delivered to backend") {
		t.Fatalf("logged %v", got)
	}
}

// loggedRun delivers body through a pipeline whose backend answers result and
// returns the decoded log lines and the raw log text.
func loggedRun(
	t *testing.T,
	body string,
	result error,
) ([]map[string]any, string) {
	t.Helper()
	logs := &syncBuffer{}
	h := &harness{
		source: &fakeSource{deliveries: make(chan vehicles.Delivery)},
		client: &scriptedClient{results: []error{result}},
		rec:    &fakeRecorder{},
		done:   make(chan error, 1),
		logger: slog.New(slog.NewJSONHandler(logs, nil)),
	}
	h.run(t, h.client, requestid.New())
	d := newDelivery(body)
	h.deliver(t, d)
	h.waitSettled(t, d)
	logs.mu.Lock()
	defer logs.mu.Unlock()
	var lines []map[string]any
	for _, raw := range bytes.Split(bytes.TrimSpace(logs.buf.Bytes()), []byte("\n")) {
		var line map[string]any
		if err := json.Unmarshal(raw, &line); err != nil {
			t.Fatalf("log line %q: %v", raw, err)
		}
		lines = append(lines, line)
	}
	return lines, logs.buf.String()
}

func TestDroppedUnclassifiableMessageIsLoggedWithoutItsPayload(
	t *testing.T,
) {
	for _, body := range []string{`{"status":"PII-MARKER"}`, `PII-MARKER {`} {
		lines, raw := loggedRun(t, body, nil)
		if strings.Contains(raw, "PII-MARKER") {
			t.Fatalf("log leaks the payload: %s", raw)
		}
		if len(lines) != 1 || lines[0]["msg"] != "dropping unclassifiable message" ||
			lines[0]["payload_bytes"] != float64(len(body)) || lines[0]["reason"] == nil {
			t.Fatalf("logged %v, want the size and a reason", lines)
		}
	}
}

func TestRejectedMessageIsLoggedWithItsStatusButNotItsPayloadOrBody(
	t *testing.T,
) {
	body := `{"vehicle_id":"VHC-001","status":"idle","note":"PII-MARKER"}`
	lines, raw := loggedRun(t, body, &backend.RejectedError{Status: 409, Body: "PII-MARKER"})
	if strings.Contains(raw, "PII-MARKER") {
		t.Fatalf("log leaks the payload or the backend body: %s", raw)
	}
	last := lines[len(lines)-1]
	if last["msg"] != "backend rejected message, dropping" || last["status"] != float64(409) || last["body_bytes"] == nil {
		t.Fatalf("logged %v, want the status and the size", lines)
	}
}
