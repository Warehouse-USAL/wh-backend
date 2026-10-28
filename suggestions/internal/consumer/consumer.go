// Package consumer reads order.completed from Kafka and applies each event to Redis.
package consumer

import (
	"context"
	"errors"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/segmentio/kafka-go"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
)

// Reader is the part of *kafka.Reader the consumer uses (a fake in tests).
type Reader interface {
	FetchMessage(ctx context.Context) (kafka.Message, error)
	CommitMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

// ApplyFunc applies one decoded event (ingest.Apply bound to a Redis client in production).
type ApplyFunc func(ctx context.Context, ev ingest.OrderCompleted) error

const (
	// pollEvery bounds how long one fetch blocks, so the loop proves it is alive on an idle topic.
	pollEvery = 5 * time.Second
	// staleAfter is how long without a completed loop turn before Alive reports false.
	staleAfter = 3 * pollEvery
)

// Consumer is the at-least-once Kafka loop: fetch, apply, commit. The offset is committed only
// after the event is in Redis; a Redis failure retries the same message with backoff instead of
// skipping it, and an undecodable one is logged and skipped (it can never succeed).
type Consumer struct {
	reader  Reader
	apply   ApplyFunc
	log     *slog.Logger
	backoff time.Duration
	now     func() time.Time
	beat    atomic.Int64 // unix nanos of the last loop turn
	running atomic.Bool
}

// New builds a Consumer.
func New(r Reader, apply ApplyFunc, log *slog.Logger) *Consumer {
	return &Consumer{reader: r, apply: apply, log: log, backoff: time.Second, now: time.Now}
}

// Alive reports whether the loop is running and has turned recently. It is what /healthz asks.
func (c *Consumer) Alive() bool {
	if !c.running.Load() {
		return false
	}
	return c.now().Sub(time.Unix(0, c.beat.Load())) < staleAfter
}

// Run consumes until ctx is done, then closes the reader. It always returns ctx's error.
func (c *Consumer) Run(ctx context.Context) error {
	c.running.Store(true)
	c.beat.Store(c.now().UnixNano())
	defer c.running.Store(false)
	defer func() {
		if err := c.reader.Close(); err != nil {
			c.log.Warn("closing kafka reader", "error", err)
		}
	}()
	for ctx.Err() == nil {
		c.beat.Store(c.now().UnixNano())
		c.step(ctx)
	}
	return ctx.Err()
}

// step handles at most one message.
func (c *Consumer) step(ctx context.Context) {
	fetchCtx, cancel := context.WithTimeout(ctx, pollEvery)
	msg, err := c.reader.FetchMessage(fetchCtx)
	cancel()
	if err != nil {
		if ctx.Err() == nil && !errors.Is(err, context.DeadlineExceeded) {
			c.log.Error("fetching from kafka", "error", err)
			c.sleep(ctx)
		}
		return
	}
	ev, err := ingest.Decode(msg.Value)
	if err != nil {
		c.log.Warn("skipping undecodable message", "offset", msg.Offset, "partition", msg.Partition, "error", err)
	} else {
		for {
			if err = c.apply(ctx, ev); err == nil {
				break
			}
			if ctx.Err() != nil {
				return // not committed: redelivered after restart
			}
			c.log.Error("applying event, retrying", "orderId", ev.OrderID, "error", err)
			c.beat.Store(c.now().UnixNano())
			c.sleep(ctx)
		}
	}
	if err = c.reader.CommitMessages(ctx, msg); err != nil && ctx.Err() == nil {
		c.log.Error("committing offset", "offset", msg.Offset, "error", err)
	}
}

func (c *Consumer) sleep(ctx context.Context) {
	t := time.NewTimer(c.backoff)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}
