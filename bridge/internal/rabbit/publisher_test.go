package rabbit

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
)

const body = `{"order_id":"ORD-1"}`

func TestHeadersCarryTheContextRequestID(
	t *testing.T,
) {
	got := headers(requestid.With(context.Background(), "req-1"))
	if len(got) != 1 || got["x-request-id"] != "req-1" {
		t.Fatalf("headers %v, want x-request-id=req-1", got)
	}
}

func TestHeadersAreNilWithoutARequestID(
	t *testing.T,
) {
	if got := headers(context.Background()); got != nil {
		t.Fatalf("headers %v, want nil", got)
	}
}

func acking() *fakeConnection {
	return &fakeConnection{channel: &fakeChannel{confirm: &fakeConfirmation{acked: true}}}
}

func newTestPublisher(
	connections ...*fakeConnection,
) (*publisher, *fakeBroker) {
	broker := &fakeBroker{connections: connections}
	return &publisher{url: "amqp://rabbit", exchange: "amq.topic", routingKey: "orders", dial: broker.dial}, broker
}

func TestHeadersAreCopiedOntoThePublishing(
	t *testing.T,
) {
	conn := acking()
	p, broker := newTestPublisher(conn)
	if err := p.Publish(requestid.With(context.Background(), "req-1"), []byte(body)); err != nil {
		t.Fatalf("Publish returned %v", err)
	}
	got := conn.channel.published[0]
	if got.exchange != "amq.topic" || got.routingKey != "orders" || !got.mandatory || got.immediate {
		t.Fatalf("published %+v, want a mandatory publish to amq.topic/orders", got)
	}
	msg := got.msg
	if msg.ContentType != "application/json" || msg.DeliveryMode != amqp.Persistent || string(msg.Body) != body || msg.Timestamp.IsZero() {
		t.Fatalf("publishing %+v", msg)
	}
	if len(msg.Headers) != 1 || msg.Headers["x-request-id"] != "req-1" {
		t.Fatalf("headers %v, want x-request-id=req-1", msg.Headers)
	}
	if !conn.channel.confirming || broker.urls[0] != "amqp://rabbit" {
		t.Fatalf("confirming=%v dialed %v", conn.channel.confirming, broker.urls)
	}
}

func TestPublishWaitsForTheConfirmAndReportsNack(
	t *testing.T,
) {
	release := make(chan struct{})
	nacking := &fakeConnection{channel: &fakeChannel{confirm: &fakeConfirmation{acked: false, wait: release}}}
	p, broker := newTestPublisher(nacking, acking())
	done := make(chan error, 1)
	go func() { done <- p.Publish(context.Background(), []byte(body)) }()
	select {
	case err := <-done:
		t.Fatalf("Publish returned %v before the broker confirmed", err)
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-done; !errors.Is(err, errNacked) {
		t.Fatalf("Publish returned %v, want errNacked", err)
	}
	if nacking.closed() != 1 {
		t.Fatalf("connection closed %d times after a nack, want 1", nacking.closed())
	}
	if err := p.Publish(context.Background(), []byte(body)); err != nil || broker.dialed() != 2 {
		t.Fatalf("retry returned %v after %d dials, want a fresh connection", err, broker.dialed())
	}
}

func TestUnroutableMessageKeepsTheConnection(
	t *testing.T,
) {
	conn := acking()
	conn.channel.returned = &amqp.Return{ReplyCode: 312, ReplyText: "NO_ROUTE"}
	p, broker := newTestPublisher(conn)
	err := p.Publish(context.Background(), []byte(body))
	if !errors.Is(err, errUnroutable) || !strings.Contains(err.Error(), "312 NO_ROUTE") {
		t.Fatalf("Publish returned %v, want errUnroutable with the reply", err)
	}
	if err = p.Publish(context.Background(), []byte(body)); err != nil {
		t.Fatalf("second Publish returned %v", err)
	}
	if conn.closed() != 0 || broker.dialed() != 1 {
		t.Fatalf("closed=%d dials=%d, want the connection kept", conn.closed(), broker.dialed())
	}
}

func TestPublishReusesTheOpenChannelAndRedialsAClosedOne(
	t *testing.T,
) {
	first, second := acking(), acking()
	p, broker := newTestPublisher(first, second)
	for range 2 {
		if err := p.Publish(context.Background(), []byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	if broker.dialed() != 1 {
		t.Fatalf("dialed %d times for an open channel, want 1", broker.dialed())
	}
	first.channel.closed = true
	if err := p.Publish(context.Background(), []byte(body)); err != nil {
		t.Fatal(err)
	}
	if broker.dialed() != 2 || first.closed() != 1 || len(second.channel.published) != 1 {
		t.Fatalf("dials=%d first closed=%d, want a redial after the channel closed", broker.dialed(), first.closed())
	}
}

type publishFailure struct {
	name   string
	conn   *fakeConnection
	want   string
	closes int
}

func (c publishFailure) run(
	t *testing.T,
) {
	p, _ := newTestPublisher(c.conn)
	err := p.Publish(context.Background(), []byte(body))
	if err == nil || !strings.Contains(err.Error(), c.want) || !errors.Is(err, errBroker) {
		t.Fatalf("Publish returned %v, want %q wrapping the broker error", err, c.want)
	}
	if c.conn != nil && c.conn.closed() != c.closes {
		t.Fatalf("connection closed %d times, want %d", c.conn.closed(), c.closes)
	}
	if p.conn != nil || p.ch != nil {
		t.Fatal("publisher kept a failed connection")
	}
}

func TestPublishFailuresDropTheConnection(
	t *testing.T,
) {
	cases := []publishFailure{
		{name: "dial", want: "dial rabbitmq"},
		{name: "channel", conn: &fakeConnection{channelErr: errBroker}, want: "open channel", closes: 1},
		{name: "confirm mode", conn: &fakeConnection{channel: &fakeChannel{confirmErr: errBroker}}, want: "enable publisher confirms", closes: 1},
		{name: "publish", conn: &fakeConnection{channel: &fakeChannel{publishErr: errBroker}}, want: "publish to amq.topic/orders", closes: 1},
		{name: "confirm wait", conn: &fakeConnection{channel: &fakeChannel{confirm: &fakeConfirmation{err: errBroker}}}, want: "wait for broker confirm", closes: 1},
	}
	for _, c := range cases {
		t.Run(c.name, c.run)
	}
}

func TestDialFailsWithoutABroker(
	t *testing.T,
) {
	if _, err := dial("amqp://guest:guest@127.0.0.1:1/"); err == nil {
		t.Fatal("expected a dial error with nothing listening")
	}
}
