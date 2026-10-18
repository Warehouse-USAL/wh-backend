package rabbit

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"

	amqp "github.com/rabbitmq/amqp091-go"
)

var errBroker = errors.New("broker failure")

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func fmtCall(
	name string,
	args ...any,
) string {
	return name + fmt.Sprintf("%v", args)
}

type fakeConfirmation struct {
	acked bool
	err   error
	wait  chan struct{}
}

func (c *fakeConfirmation) WaitContext(
	ctx context.Context,
) (bool, error) {
	if c.wait == nil {
		return c.acked, c.err
	}
	select {
	case <-ctx.Done():
		return false, ctx.Err()
	case <-c.wait:
		return c.acked, c.err
	}
}

type published struct {
	exchange   string
	routingKey string
	mandatory  bool
	immediate  bool
	msg        amqp.Publishing
}

type fakeChannel struct {
	mu sync.Mutex

	confirmErr error
	publishErr error
	confirm    *fakeConfirmation
	returned   *amqp.Return
	closed     bool
	published  []published
	confirming bool

	declareErr error
	bindErr    error
	qosErr     error
	consumeErr error
	deliveries chan amqp.Delivery
	calls      []string
}

func (c *fakeChannel) record(
	call string,
) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.calls = append(c.calls, call)
}

func (c *fakeChannel) Confirm(
	noWait bool,
) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.confirming = !noWait
	return c.confirmErr
}

func (c *fakeChannel) NotifyReturn(
	returns chan amqp.Return,
) chan amqp.Return {
	c.mu.Lock()
	returned := c.returned
	c.mu.Unlock()
	if returned != nil {
		returns <- *returned
	}
	return returns
}

func (c *fakeChannel) IsClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

func (c *fakeChannel) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
}

func (c *fakeChannel) returnWith(
	r amqp.Return,
) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.returned = &r
}

func (c *fakeChannel) isConfirming() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.confirming
}

func (c *fakeChannel) publications() []published {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]published(nil), c.published...)
}

func (c *fakeChannel) PublishConfirmed(
	_ context.Context,
	exchange string,
	routingKey string,
	mandatory bool,
	immediate bool,
	msg amqp.Publishing,
) (confirmation, error) {
	c.mu.Lock()
	c.published = append(c.published, published{exchange, routingKey, mandatory, immediate, msg})
	c.mu.Unlock()
	if c.publishErr != nil {
		return nil, c.publishErr
	}
	return c.confirm, nil
}

func (c *fakeChannel) QueueDeclare(
	name string,
	durable bool,
	autoDelete bool,
	exclusive bool,
	noWait bool,
	_ amqp.Table,
) (amqp.Queue, error) {
	c.record(fmtCall("declare", name, durable, autoDelete, exclusive, noWait))
	return amqp.Queue{Name: name}, c.declareErr
}

func (c *fakeChannel) QueueBind(
	name string,
	key string,
	exchange string,
	noWait bool,
	_ amqp.Table,
) error {
	c.record(fmtCall("bind", name, key, exchange, noWait))
	return c.bindErr
}

func (c *fakeChannel) Qos(
	prefetchCount int,
	prefetchSize int,
	global bool,
) error {
	c.record(fmtCall("qos", prefetchCount, prefetchSize, global))
	return c.qosErr
}

func (c *fakeChannel) Consume(
	queue string,
	consumer string,
	autoAck bool,
	exclusive bool,
	noLocal bool,
	noWait bool,
	_ amqp.Table,
) (<-chan amqp.Delivery, error) {
	c.record(fmtCall("consume", queue, consumer, autoAck, exclusive, noLocal, noWait))
	return c.deliveries, c.consumeErr
}

func (c *fakeChannel) history() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.calls...)
}

type fakeConnection struct {
	mu         sync.Mutex
	channel    *fakeChannel
	channelErr error
	closes     int
}

func (c *fakeConnection) Channel() (channel, error) {
	if c.channelErr != nil {
		return nil, c.channelErr
	}
	return c.channel, nil
}

func (c *fakeConnection) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closes++
	return nil
}

func (c *fakeConnection) closed() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closes
}

// fakeBroker hands out the scripted connections in order, failing a dial
// for every nil entry, and repeats the last one once the script runs out.
type fakeBroker struct {
	mu          sync.Mutex
	connections []*fakeConnection
	dials       int
	urls        []string
}

func (b *fakeBroker) dial(
	url string,
) (connection, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.urls = append(b.urls, url)
	next := b.connections[min(b.dials, len(b.connections)-1)]
	b.dials++
	if next == nil {
		return nil, errBroker
	}
	return next, nil
}

func (b *fakeBroker) dialed() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.dials
}

type acknowledgement struct {
	tag      uint64
	ack      bool
	multiple bool
	requeue  bool
}

type fakeAcknowledger struct {
	mu   sync.Mutex
	acks []acknowledgement
	seen chan struct{}
}

func (a *fakeAcknowledger) Ack(
	tag uint64,
	multiple bool,
) error {
	return a.add(acknowledgement{tag: tag, ack: true, multiple: multiple})
}

func (a *fakeAcknowledger) Nack(
	tag uint64,
	multiple bool,
	requeue bool,
) error {
	return a.add(acknowledgement{tag: tag, multiple: multiple, requeue: requeue})
}

func (a *fakeAcknowledger) Reject(
	tag uint64,
	requeue bool,
) error {
	return a.add(acknowledgement{tag: tag, requeue: requeue})
}

func (a *fakeAcknowledger) add(
	ack acknowledgement,
) error {
	a.mu.Lock()
	a.acks = append(a.acks, ack)
	a.mu.Unlock()
	a.seen <- struct{}{}
	return nil
}

func (a *fakeAcknowledger) recorded() []acknowledgement {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]acknowledgement(nil), a.acks...)
}
