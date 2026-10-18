package orders

import (
	"context"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

const flow = "orders_out"

// Pipeline moves orders from the backend to Infra, one at a time.
type Pipeline interface {
	// Run publishes every record until ctx ends.
	//
	// A record is committed only after the broker confirmed it, so an order
	// is never lost; publish failures are retried with backoff. Invalid
	// records are logged and skipped. Run returns nil on a clean stop and
	// the consumer error otherwise.
	Run(ctx context.Context) error
}

// New builds a Pipeline that reads from consumer and writes to publisher,
// retrying failed publishes according to policy. Every record is counted on
// rec under the orders_out flow: ok once published, retry per failed
// attempt, dropped when invalid and error when stopped before publishing.
func New(
	consumer Consumer,
	publisher Publisher,
	policy retry.Policy,
	logger *slog.Logger,
	rec telemetry.Recorder,
) Pipeline {
	policy.OnRetry = func(
		err error,
		delay time.Duration,
	) {
		logger.Warn("rabbitmq publish failed, retrying", "error", err, "retry_in", delay)
		rec.Message(flow, "retry")
	}
	return &runner{consumer: consumer, publisher: publisher, policy: policy, logger: logger, rec: rec}
}
