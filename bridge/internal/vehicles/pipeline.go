package vehicles

import (
	"context"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

// Pipeline moves messages from Infra to the backend, one at a time.
type Pipeline interface {
	// Run processes messages until ctx ends or the source closes.
	//
	// A message is acked once the backend accepts it or rejects it for good.
	// Transient failures are retried with backoff and the message is
	// requeued if ctx ends meanwhile. Run returns nil on a clean stop.
	Run(ctx context.Context) error
}

// New builds a Pipeline that reads from source and reports to client,
// retrying transient failures according to policy.
func New(
	source Source,
	client backend.Client,
	policy retry.Policy,
	logger *slog.Logger,
) Pipeline {
	policy.OnRetry = func(
		err error,
		delay time.Duration,
	) {
		logger.Warn("backend unavailable, retrying", "error", err, "retry_in", delay)
	}
	return &runner{source: source, client: client, policy: policy, logger: logger}
}
