package rabbit

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

const settleTimeout = 2 * time.Second

var (
	fastPolicy = retry.Policy{Min: time.Millisecond, Max: 2 * time.Millisecond}
	binding    = Binding{Exchange: "amq.topic", Queue: "bridge.vehicles", RoutingKey: "vehicles"}
)

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

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func consuming() *fakeConnection {
	return &fakeConnection{channel: &fakeChannel{deliveries: make(chan amqp.Delivery)}}
}

func newTestConsumer(
	logs *syncBuffer,
	connections ...*fakeConnection,
) (*consumer, *fakeBroker) {
	broker := &fakeBroker{connections: connections}
	logger := slog.New(slog.NewTextHandler(logs, nil))
	return &consumer{url: "amqp://rabbit", binding: binding, policy: fastPolicy, logger: logger, dial: broker.dial}, broker
}

func message(
	tag uint64,
	body string,
	acks *fakeAcknowledger,
) amqp.Delivery {
	return amqp.Delivery{Acknowledger: acks, DeliveryTag: tag, Body: []byte(body)}
}

func send(
	t *testing.T,
	deliveries chan<- amqp.Delivery,
	d amqp.Delivery,
) {
	t.Helper()
	select {
	case deliveries <- d:
	case <-time.After(settleTimeout):
		t.Fatal("consumer did not take the message")
	}
}

func receive(
	t *testing.T,
	out <-chan vehicles.Delivery,
) vehicles.Delivery {
	t.Helper()
	select {
	case d, ok := <-out:
		if !ok {
			t.Fatal("deliveries closed")
		}
		return d
	case <-time.After(settleTimeout):
		t.Fatal("no delivery forwarded")
	}
	return nil
}

func waitAck(
	t *testing.T,
	acks *fakeAcknowledger,
) {
	t.Helper()
	select {
	case <-acks.seen:
	case <-time.After(settleTimeout):
		t.Fatal("message was neither acked nor nacked")
	}
}

func waitClosed(
	t *testing.T,
	out <-chan vehicles.Delivery,
) {
	t.Helper()
	select {
	case _, ok := <-out:
		if ok {
			t.Fatal("unexpected delivery")
		}
	case <-time.After(settleTimeout):
		t.Fatal("deliveries not closed after the context ended")
	}
}

func eventually(
	t *testing.T,
	condition func() bool,
) {
	t.Helper()
	deadline := time.Now().Add(settleTimeout)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in time")
		}
		time.Sleep(time.Millisecond)
	}
}

const (
	statusBody    = `{"order_id":"ORD-1","status":"completed","vehicle_id":"VHC-001","timestamp":"t"}`
	telemetryBody = `{"vehicle_id":"VHC-001","status":"idle","battery":90,"last_qr":null,"error":null,"timestamp":"t"}`
)

func TestConsumerAcksOn2xxAndNacksOnRetry(
	t *testing.T,
) {
	attempts := make(chan string, 1024)
	server := httptest.NewServer(http.HandlerFunc(func(
		w http.ResponseWriter,
		r *http.Request,
	) {
		attempts <- r.URL.Path
		if strings.HasPrefix(r.URL.Path, "/infra/orders/") {
			return
		}
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(server.Close)
	conn := consuming()
	c, _ := newTestConsumer(&syncBuffer{}, conn)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	pipeline := vehicles.New(c, backend.New(server.URL, "secret", time.Second), fastPolicy, quiet, telemetry.Noop(), requestid.New())
	done := make(chan error, 1)
	go func() { done <- pipeline.Run(ctx) }()
	acks := &fakeAcknowledger{seen: make(chan struct{}, 4)}
	send(t, conn.channel.deliveries, message(1, statusBody, acks))
	waitAck(t, acks)
	send(t, conn.channel.deliveries, message(2, telemetryBody, acks))
	for range 2 {
		<-attempts
	}
	cancel()
	waitAck(t, acks)
	if err := <-done; err != nil {
		t.Fatalf("Run returned %v", err)
	}
	want := []acknowledgement{{tag: 1, ack: true}, {tag: 2, requeue: true}}
	if got := acks.recorded(); len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("acknowledgements %+v, want %+v", got, want)
	}
}

func TestSessionDeclaresBindsAndConsumesWithManualAcks(
	t *testing.T,
) {
	conn := consuming()
	c, broker := newTestConsumer(&syncBuffer{}, conn)
	ctx, cancel := context.WithCancel(context.Background())
	out := c.Deliveries(ctx)
	acks := &fakeAcknowledger{seen: make(chan struct{}, 1)}
	send(t, conn.channel.deliveries, message(7, telemetryBody, acks))
	if got := receive(t, out); string(got.Body()) != telemetryBody {
		t.Fatalf("forwarded %q", got.Body())
	}
	cancel()
	waitClosed(t, out)
	want := []string{
		"declare[bridge.vehicles true false false false]",
		"bind[bridge.vehicles vehicles amq.topic false]",
		"qos[1 0 false]",
		"consume[bridge.vehicles  false false false false]",
	}
	if got := conn.channel.history(); strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("calls %v, want %v", got, want)
	}
	if conn.closed() != 1 || broker.urls[0] != "amqp://rabbit" {
		t.Fatalf("closed=%d urls=%v, want the session connection closed", conn.closed(), broker.urls)
	}
}

func TestDeliveryAcksAndRequeuesThroughTheBroker(
	t *testing.T,
) {
	acks := &fakeAcknowledger{seen: make(chan struct{}, 2)}
	if err := (&delivery{message: message(3, "", acks)}).Ack(); err != nil {
		t.Fatal(err)
	}
	if err := (&delivery{message: message(4, "", acks)}).Requeue(); err != nil {
		t.Fatal(err)
	}
	want := []acknowledgement{{tag: 3, ack: true}, {tag: 4, requeue: true}}
	if got := acks.recorded(); len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("acknowledgements %+v, want %+v", got, want)
	}
}

func TestConsumerReconnectsAfterTheConnectionIsLost(
	t *testing.T,
) {
	first, second := consuming(), consuming()
	logs := &syncBuffer{}
	c, broker := newTestConsumer(logs, nil, first, second)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	out := c.Deliveries(ctx)
	acks := &fakeAcknowledger{seen: make(chan struct{}, 2)}
	send(t, first.channel.deliveries, message(1, statusBody, acks))
	receive(t, out)
	close(first.channel.deliveries)
	send(t, second.channel.deliveries, message(2, telemetryBody, acks))
	if got := receive(t, out); string(got.Body()) != telemetryBody {
		t.Fatalf("forwarded %q after reconnecting", got.Body())
	}
	if broker.dialed() != 3 || first.closed() != 1 {
		t.Fatalf("dials=%d first closed=%d, want a failed dial, a session and a reconnect", broker.dialed(), first.closed())
	}
	for _, want := range []string{"dial rabbitmq", "rabbitmq connection lost", "reconnecting"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("logs %q, want %q", logs.String(), want)
		}
	}
}

type setupFailure struct {
	name string
	conn *fakeConnection
	want string
}

func (f setupFailure) run(
	t *testing.T,
) {
	logs := &syncBuffer{}
	healthy := consuming()
	c, broker := newTestConsumer(logs, f.conn, healthy)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	out := c.Deliveries(ctx)
	acks := &fakeAcknowledger{seen: make(chan struct{}, 1)}
	send(t, healthy.channel.deliveries, message(1, telemetryBody, acks))
	receive(t, out)
	if !strings.Contains(logs.String(), f.want) || broker.dialed() != 2 || f.conn.closed() != 1 {
		t.Fatalf("logs %q dials=%d closed=%d, want %q then a reconnect", logs.String(), broker.dialed(), f.conn.closed(), f.want)
	}
}

func failing(
	configure func(*fakeChannel),
) *fakeConnection {
	conn := consuming()
	configure(conn.channel)
	return conn
}

func TestSetupFailuresAreRetried(
	t *testing.T,
) {
	cases := []setupFailure{
		{name: "channel", conn: &fakeConnection{channelErr: errBroker}, want: "open channel"},
		{name: "declare", conn: failing(func(ch *fakeChannel) { ch.declareErr = errBroker }), want: "declare queue bridge.vehicles"},
		{name: "bind", conn: failing(func(ch *fakeChannel) { ch.bindErr = errBroker }), want: "bind queue bridge.vehicles to amq.topic/vehicles"},
		{name: "qos", conn: failing(func(ch *fakeChannel) { ch.qosErr = errBroker }), want: "set prefetch"},
		{name: "consume", conn: failing(func(ch *fakeChannel) { ch.consumeErr = errBroker }), want: "consume bridge.vehicles"},
	}
	for _, c := range cases {
		t.Run(c.name, c.run)
	}
}

func TestPendingDeliveryIsDroppedWhenTheContextEnds(
	t *testing.T,
) {
	conn := consuming()
	c, _ := newTestConsumer(&syncBuffer{}, conn)
	ctx, cancel := context.WithCancel(context.Background())
	out := c.Deliveries(ctx)
	acks := &fakeAcknowledger{seen: make(chan struct{}, 1)}
	send(t, conn.channel.deliveries, message(1, telemetryBody, acks))
	cancel()
	eventually(t, func() bool { return conn.closed() == 1 })
	waitClosed(t, out)
	if got := acks.recorded(); len(got) != 0 {
		t.Fatalf("acknowledged %+v, want the message left for redelivery", got)
	}
}

func TestConstructorsUseTheRealDialer(
	t *testing.T,
) {
	if c, ok := NewConsumer("amqp://rabbit", binding, fastPolicy, quiet).(*consumer); !ok || c.dial == nil || c.binding != binding {
		t.Fatalf("NewConsumer built %+v", c)
	}
	if p, ok := NewPublisher("amqp://rabbit", "amq.topic", "orders").(*publisher); !ok || p.dial == nil || p.routingKey != "orders" {
		t.Fatalf("NewPublisher built %+v", p)
	}
}
