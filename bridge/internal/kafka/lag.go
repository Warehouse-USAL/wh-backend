package kafka

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

const (
	lagFlow     = "orders_out"
	lagInterval = 10 * time.Second
)

// offsetAdmin is the part of *kadm.Client the lag reporter uses.
type offsetAdmin interface {
	ListStartOffsets(ctx context.Context, topics ...string) (kadm.ListedOffsets, error)
	ListEndOffsets(ctx context.Context, topics ...string) (kadm.ListedOffsets, error)
	FetchOffsets(ctx context.Context, group string) (kadm.OffsetResponses, error)
}

// ReportLag reports how many records of topic group has not committed yet,
// as the orders_out consumer lag on rec, every 10 seconds until ctx ends.
//
// It uses its own admin connection to brokers. Failed rounds are logged at
// debug and skipped. It returns nil once ctx ends, and also right away when
// the admin client cannot be built, after logging the error: a missing lag
// metric must never stop the bridge.
func ReportLag(
	ctx context.Context,
	brokers []string,
	group string,
	topic string,
	rec telemetry.Recorder,
	logger *slog.Logger,
) error {
	client, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		logger.Error("consumer lag reporting disabled", "error", fmt.Errorf("create kafka admin client: %w", err))
		return nil
	}
	defer client.Close()
	return lagReporter(ctx, kadm.NewClient(client), group, topic, rec, lagInterval, logger)
}

func lagReporter(
	ctx context.Context,
	admin offsetAdmin,
	group string,
	topic string,
	rec telemetry.Recorder,
	every time.Duration,
	logger *slog.Logger,
) error {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		lag, err := consumerLag(ctx, admin, group, topic)
		if err != nil {
			logger.Debug("consumer lag unavailable", "error", err)
		} else {
			rec.Lag(lagFlow, lag)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

// consumerLag sums, over the partitions of topic, the end offset minus the
// offset group will resume from.
//
// The resume offset is the committed offset, or the partition's start
// offset when group has not committed there yet: the consumer starts a
// fresh group at the beginning of the topic, so everything retained is
// still waiting. A commit older than the retained start counts from the
// start too, since the records before it no longer exist.
func consumerLag(
	ctx context.Context,
	admin offsetAdmin,
	group string,
	topic string,
) (int64, error) {
	ends, err := listOffsets(ctx, admin.ListEndOffsets, topic)
	if err != nil {
		return 0, err
	}
	starts, err := listOffsets(ctx, admin.ListStartOffsets, topic)
	if err != nil {
		return 0, err
	}
	commits, err := admin.FetchOffsets(ctx, group)
	if err == nil {
		err = commits.Error()
	}
	if err != nil {
		return 0, fmt.Errorf("fetch committed offsets: %w", err)
	}
	var total int64
	for partition, end := range ends[topic] {
		from := starts[topic][partition].Offset
		if commit, ok := commits.Lookup(topic, partition); ok {
			from = max(from, commit.At)
		}
		total += max(end.Offset-from, 0)
	}
	return total, nil
}

func listOffsets(
	ctx context.Context,
	list func(context.Context, ...string) (kadm.ListedOffsets, error),
	topic string,
) (kadm.ListedOffsets, error) {
	offsets, err := list(ctx, topic)
	if err == nil {
		err = offsets.Error()
	}
	if err != nil {
		return nil, fmt.Errorf("list offsets: %w", err)
	}
	return offsets, nil
}
