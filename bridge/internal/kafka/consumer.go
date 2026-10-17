package kafka

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
)

const errorPause = 500 * time.Millisecond

var errClosed = errors.New("kafka client closed")

type consumer struct {
	client *kgo.Client
	buffer []*kgo.Record
	logger *slog.Logger
}

func (c *consumer) Poll(
	ctx context.Context,
) (orders.Record, error) {
	var err error
	for len(c.buffer) == 0 && err == nil {
		err = c.fill(ctx)
	}
	if err != nil {
		return nil, err
	}
	next := c.buffer[0]
	c.buffer = c.buffer[1:]
	return &record{raw: next}, nil
}

func (c *consumer) fill(
	ctx context.Context,
) error {
	fetches := c.client.PollFetches(ctx)
	if fetches.IsClientClosed() {
		return errClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	c.buffer = append(c.buffer, fetches.Records()...)
	return c.logErrors(ctx, fetches)
}

func (c *consumer) logErrors(
	ctx context.Context,
	fetches kgo.Fetches,
) error {
	failures := fetches.Errors()
	for _, failure := range failures {
		c.logger.Warn("kafka fetch error", "topic", failure.Topic, "partition", failure.Partition, "error", failure.Err)
	}
	if len(failures) == 0 || len(c.buffer) > 0 {
		return nil
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(errorPause):
		return nil
	}
}

func (c *consumer) Commit(
	ctx context.Context,
	rec orders.Record,
) error {
	typed, ok := rec.(*record)
	if !ok {
		return fmt.Errorf("unexpected record type %T", rec)
	}
	return c.client.CommitRecords(ctx, typed.raw)
}

func (c *consumer) Close() {
	c.client.Close()
}
