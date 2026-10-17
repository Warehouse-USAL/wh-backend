// Package kafka adapts Kafka to the ports the pipelines depend on.
package kafka

import (
	"fmt"
	"log/slog"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
)

// NewConsumer joins group and reads topic with automatic commits disabled,
// so offsets only advance when the pipeline calls Commit.
//
// A group without committed offsets starts from the beginning of the topic,
// which means orders created before the bridge came up are not skipped.
// Creating the consumer does not contact the brokers, so it succeeds even
// while they are down and connects once they are reachable.
func NewConsumer(
	brokers []string,
	topic string,
	group string,
	logger *slog.Logger,
) (orders.Consumer, error) {
	client, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.DisableAutoCommit(),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
	)
	if err != nil {
		return nil, fmt.Errorf("create kafka client: %w", err)
	}
	return &consumer{client: client, logger: logger}, nil
}
